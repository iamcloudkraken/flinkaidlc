package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.*;
import com.flinkaidlc.platform.exception.PipelineNotFoundException;
import com.flinkaidlc.platform.exception.ResourceLimitExceededException;
import com.flinkaidlc.platform.exception.TenantNotFoundException;
import com.flinkaidlc.platform.orchestration.FlinkOrchestrationService;
import com.flinkaidlc.platform.repository.PipelineDeploymentRepository;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository pipelineRepository;
    private final PipelineDeploymentRepository pipelineDeploymentRepository;
    private final TenantRepository tenantRepository;
    private final SqlValidationService sqlValidationService;
    private final SchemaRegistryValidationService schemaRegistryValidationService;
    private final FlinkOrchestrationService orchestrationService;

    public PipelineService(
            PipelineRepository pipelineRepository,
            PipelineDeploymentRepository pipelineDeploymentRepository,
            TenantRepository tenantRepository,
            SqlValidationService sqlValidationService,
            SchemaRegistryValidationService schemaRegistryValidationService,
            FlinkOrchestrationService orchestrationService) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineDeploymentRepository = pipelineDeploymentRepository;
        this.tenantRepository = tenantRepository;
        this.sqlValidationService = sqlValidationService;
        this.schemaRegistryValidationService = schemaRegistryValidationService;
        this.orchestrationService = orchestrationService;
    }

    public PipelineResponse createPipeline(UUID tenantId, CreatePipelineRequest request) {
        // 1. Fetch tenant
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        // 2. Check max pipelines limit (resource limits checked BEFORE any DB write)
        long activePipelineCount = pipelineRepository.countByTenantIdAndStatusNot(tenantId, PipelineStatus.DELETED);
        if (activePipelineCount >= tenant.getMaxPipelines()) {
            throw new ResourceLimitExceededException(
                    "Tenant has reached the maximum pipeline limit of " + tenant.getMaxPipelines());
        }

        // 3. Check total parallelism limit
        long usedParallelism = pipelineRepository.sumParallelismByTenantIdExcludingStatuses(
                tenantId, List.of(PipelineStatus.DELETED));
        if (usedParallelism + request.parallelism() > tenant.getMaxTotalParallelism()) {
            throw new ResourceLimitExceededException(
                    "Adding parallelism " + request.parallelism() +
                    " would exceed the tenant's total parallelism limit of " + tenant.getMaxTotalParallelism());
        }

        // 4. Validate SQL
        List<String> allTableNames = Stream.concat(
                request.sources().stream().map(PipelineSourceRequest::tableName),
                request.sinks().stream().map(PipelineSinkRequest::tableName)
        ).collect(Collectors.toList());
        sqlValidationService.validate(request.sqlQuery(), allTableNames);

        // 5. Validate each Kafka source and sink schema registry (skip for S3)
        for (PipelineSourceRequest source : request.sources()) {
            if (source instanceof KafkaSourceRequest kafka) {
                schemaRegistryValidationService.validate(kafka.schemaRegistryUrl(), kafka.avroSubject());
            }
        }
        for (PipelineSinkRequest sink : request.sinks()) {
            if (sink instanceof KafkaSinkRequest kafka) {
                schemaRegistryValidationService.validate(kafka.schemaRegistryUrl(), kafka.avroSubject());
            }
        }

        // 6. Persist Pipeline (status=DRAFT)
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(tenantId);
        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setSqlQuery(request.sqlQuery());
        pipeline.setParallelism(request.parallelism());
        pipeline.setCheckpointIntervalMs(request.checkpointIntervalMs());
        pipeline.setUpgradeMode(request.upgradeMode());
        pipeline.setStatus(PipelineStatus.DRAFT);

        for (PipelineSourceRequest sourceReq : request.sources()) {
            PipelineSource source = buildSourceEntity(sourceReq);
            pipeline.addSource(source);
        }

        for (PipelineSinkRequest sinkReq : request.sinks()) {
            PipelineSink sink = buildSinkEntity(sinkReq);
            pipeline.addSink(sink);
        }

        pipeline = pipelineRepository.save(pipeline);

        // 7. Call orchestrationService.deploy — on exception set status=FAILED
        try {
            orchestrationService.deploy(pipeline);
        } catch (Exception e) {
            log.error("Failed to deploy pipeline {}: {}", pipeline.getPipelineId(), e.getMessage(), e);
            pipeline.setStatus(PipelineStatus.FAILED);
            pipeline = pipelineRepository.save(pipeline);
            return PipelineResponse.from(pipeline);
        }

        // 8. Set status=DEPLOYING
        pipeline.setStatus(PipelineStatus.DEPLOYING);
        pipeline = pipelineRepository.save(pipeline);
        return PipelineResponse.from(pipeline);
    }

    @Transactional(readOnly = true)
    public PipelinePageResponse listPipelines(UUID tenantId, PipelineStatus statusFilter, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Pipeline> resultPage;
        if (statusFilter != null) {
            resultPage = pipelineRepository.findByTenantIdAndStatus(tenantId, statusFilter, pageable);
        } else {
            resultPage = pipelineRepository.findByTenantId(tenantId, pageable);
        }
        List<PipelineResponse> items = resultPage.getContent().stream()
                .map(PipelineResponse::from)
                .collect(Collectors.toList());
        return new PipelinePageResponse(items, page, size, resultPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PipelineDetailResponse getPipelineDetail(UUID tenantId, UUID pipelineId) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

        if (!pipeline.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("Access to pipeline " + pipelineId + " is denied");
        }

        List<PipelineDeployment> deployments = pipeline.getDeployment() != null
                ? List.of(pipeline.getDeployment())
                : List.of();

        return PipelineDetailResponse.from(pipeline, deployments);
    }

    public PipelineResponse updatePipeline(UUID tenantId, UUID pipelineId, UpdatePipelineRequest request) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

        if (!pipeline.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("Access to pipeline " + pipelineId + " is denied");
        }

        // Validate SQL
        List<String> allTableNames = Stream.concat(
                request.sources().stream().map(PipelineSourceRequest::tableName),
                request.sinks().stream().map(PipelineSinkRequest::tableName)
        ).collect(Collectors.toList());
        sqlValidationService.validate(request.sqlQuery(), allTableNames);

        // Validate schema registry entries (only Kafka)
        for (PipelineSourceRequest source : request.sources()) {
            if (source instanceof KafkaSourceRequest kafka) {
                schemaRegistryValidationService.validate(kafka.schemaRegistryUrl(), kafka.avroSubject());
            }
        }
        for (PipelineSinkRequest sink : request.sinks()) {
            if (sink instanceof KafkaSinkRequest kafka) {
                schemaRegistryValidationService.validate(kafka.schemaRegistryUrl(), kafka.avroSubject());
            }
        }

        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setSqlQuery(request.sqlQuery());
        pipeline.setParallelism(request.parallelism());
        pipeline.setCheckpointIntervalMs(request.checkpointIntervalMs());
        pipeline.setUpgradeMode(request.upgradeMode());

        // Replace sources and sinks
        pipeline.getSources().clear();
        for (PipelineSourceRequest sourceReq : request.sources()) {
            PipelineSource source = buildSourceEntity(sourceReq);
            pipeline.addSource(source);
        }
        pipeline.getSinks().clear();
        for (PipelineSinkRequest sinkReq : request.sinks()) {
            PipelineSink sink = buildSinkEntity(sinkReq);
            pipeline.addSink(sink);
        }

        try {
            orchestrationService.upgrade(pipeline);
        } catch (Exception e) {
            log.error("Failed to upgrade pipeline {}: {}", pipeline.getPipelineId(), e.getMessage(), e);
            pipeline.setStatus(PipelineStatus.FAILED);
        }

        pipeline = pipelineRepository.save(pipeline);
        return PipelineResponse.from(pipeline);
    }

    public void deletePipeline(UUID tenantId, UUID pipelineId) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

        if (!pipeline.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("Access to pipeline " + pipelineId + " is denied");
        }

        try {
            orchestrationService.teardown(pipeline);
        } catch (Exception e) {
            log.error("Failed to teardown pipeline {}: {}", pipeline.getPipelineId(), e.getMessage(), e);
        }

        pipeline.setStatus(PipelineStatus.DELETED);
        pipelineRepository.save(pipeline);
    }

    public PipelineResponse suspendPipeline(UUID tenantId, UUID pipelineId) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

        if (!pipeline.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("Access to pipeline " + pipelineId + " is denied");
        }

        try {
            orchestrationService.suspend(pipeline);
            pipeline.setStatus(PipelineStatus.SUSPENDED);
        } catch (Exception e) {
            log.error("Failed to suspend pipeline {}: {}", pipeline.getPipelineId(), e.getMessage(), e);
            pipeline.setStatus(PipelineStatus.FAILED);
        }

        pipeline = pipelineRepository.save(pipeline);
        return PipelineResponse.from(pipeline);
    }

    public PipelineResponse resumePipeline(UUID tenantId, UUID pipelineId) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

        if (!pipeline.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("Access to pipeline " + pipelineId + " is denied");
        }

        try {
            orchestrationService.resume(pipeline);
            pipeline.setStatus(PipelineStatus.DEPLOYING);
        } catch (Exception e) {
            log.error("Failed to resume pipeline {}: {}", pipeline.getPipelineId(), e.getMessage(), e);
            pipeline.setStatus(PipelineStatus.FAILED);
        }

        pipeline = pipelineRepository.save(pipeline);
        return PipelineResponse.from(pipeline);
    }

    public void suspendAll(UUID tenantId) {
        orchestrationService.suspendAll(tenantId);
    }

    // --- Private helpers ---

    private PipelineSource buildSourceEntity(PipelineSourceRequest sourceReq) {
        return switch (sourceReq) {
            case KafkaSourceRequest kafka -> {
                KafkaPipelineSource s = new KafkaPipelineSource();
                s.setTableName(kafka.tableName());
                s.setTopic(kafka.topic());
                s.setBootstrapServers(kafka.bootstrapServers());
                s.setConsumerGroup(kafka.consumerGroup());
                s.setStartupMode(kafka.startupMode() != null ? kafka.startupMode() : StartupMode.GROUP_OFFSETS);
                s.setSchemaRegistryUrl(kafka.schemaRegistryUrl());
                s.setAvroSubject(kafka.avroSubject());
                s.setWatermarkColumn(kafka.watermarkColumn());
                s.setWatermarkDelayMs(kafka.watermarkDelayMs());
                s.setColumns(kafka.columns() != null ? kafka.columns() : new ArrayList<>());
                yield s;
            }
            case S3SourceRequest s3 -> {
                S3PipelineSource s = new S3PipelineSource();
                s.setTableName(s3.tableName());
                s.setBucket(s3.bucket());
                s.setPrefix(s3.prefix());
                s.setPartitioned(s3.partitioned());
                s.setAuthType(s3.authType());
                s.setAccessKey(s3.accessKey());
                s.setSecretKey(s3.secretKey());
                s.setColumns(s3.columns() != null ? s3.columns() : new ArrayList<>());
                yield s;
            }
        };
    }

    private PipelineSink buildSinkEntity(PipelineSinkRequest sinkReq) {
        return switch (sinkReq) {
            case KafkaSinkRequest kafka -> {
                KafkaPipelineSink s = new KafkaPipelineSink();
                s.setTableName(kafka.tableName());
                s.setTopic(kafka.topic());
                s.setBootstrapServers(kafka.bootstrapServers());
                s.setSchemaRegistryUrl(kafka.schemaRegistryUrl());
                s.setAvroSubject(kafka.avroSubject());
                s.setDeliveryGuarantee(kafka.deliveryGuarantee() != null ? kafka.deliveryGuarantee() : DeliveryGuarantee.AT_LEAST_ONCE);
                s.setColumns(kafka.columns() != null ? kafka.columns() : new ArrayList<>());
                yield s;
            }
            case S3SinkRequest s3 -> {
                S3PipelineSink s = new S3PipelineSink();
                s.setTableName(s3.tableName());
                s.setBucket(s3.bucket());
                s.setPrefix(s3.prefix());
                s.setPartitioned(s3.partitioned());
                s.setAuthType(s3.authType());
                s.setAccessKey(s3.accessKey());
                s.setSecretKey(s3.secretKey());
                s.setColumns(s3.columns() != null ? s3.columns() : new ArrayList<>());
                s.setS3PartitionColumns(s3.s3PartitionColumns() != null ? s3.s3PartitionColumns() : new ArrayList<>());
                yield s;
            }
        };
    }
}
