package com.flinkaidlc.platform;

import com.flinkaidlc.platform.domain.*;
import com.flinkaidlc.platform.repository.PipelineDeploymentRepository;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class EntityPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PipelineRepository pipelineRepository;

    @Autowired
    private PipelineDeploymentRepository pipelineDeploymentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistAndRetrieveTenant() {
        Tenant tenant = new Tenant();
        tenant.setSlug("acme-corp");
        tenant.setName("ACME Corporation");
        tenant.setContactEmail("admin@acme.com");
        tenant.setFid("fid-acme-001");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setMaxPipelines(20);
        tenant.setMaxTotalParallelism(100);

        Tenant saved = tenantRepository.save(tenant);
        entityManager.flush();
        entityManager.clear();

        Tenant loaded = tenantRepository.findById(saved.getTenantId()).orElseThrow();
        assertThat(loaded.getSlug()).isEqualTo("acme-corp");
        assertThat(loaded.getName()).isEqualTo("ACME Corporation");
        assertThat(loaded.getContactEmail()).isEqualTo("admin@acme.com");
        assertThat(loaded.getFid()).isEqualTo("fid-acme-001");
        assertThat(loaded.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(loaded.getMaxPipelines()).isEqualTo(20);
        assertThat(loaded.getMaxTotalParallelism()).isEqualTo(100);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void persistAndRetrievePipelineWithSourcesAndSinks() {
        Tenant tenant = createAndSaveTenant("test-tenant-" + UUID.randomUUID().toString().substring(0, 8));

        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(tenant.getTenantId());
        pipeline.setName("Test Pipeline");
        pipeline.setDescription("A test pipeline");
        pipeline.setSqlQuery("SELECT * FROM source_table");
        pipeline.setStatus(PipelineStatus.DRAFT);
        pipeline.setParallelism(2);
        pipeline.setCheckpointIntervalMs(30000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);

        PipelineSource source = new PipelineSource();
        source.setTableName("source_table");
        source.setTopic("input-topic");
        source.setBootstrapServers("kafka:9092");
        source.setConsumerGroup("my-group");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://schema-registry:8081");
        source.setAvroSubject("input-topic-value");
        pipeline.addSource(source);

        PipelineSink sink = new PipelineSink();
        sink.setTableName("sink_table");
        sink.setTopic("output-topic");
        sink.setBootstrapServers("kafka:9092");
        sink.setSchemaRegistryUrl("http://schema-registry:8081");
        sink.setAvroSubject("output-topic-value");
        sink.setPartitioner(Partitioner.DEFAULT);
        sink.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        pipeline.addSink(sink);

        Pipeline saved = pipelineRepository.save(pipeline);
        entityManager.flush();
        entityManager.clear();

        Pipeline loaded = pipelineRepository.findById(saved.getPipelineId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Test Pipeline");
        assertThat(loaded.getSources()).hasSize(1);
        assertThat(loaded.getSinks()).hasSize(1);
        assertThat(loaded.getSources().get(0).getTopic()).isEqualTo("input-topic");
        assertThat(loaded.getSinks().get(0).getTopic()).isEqualTo("output-topic");
    }

    @Test
    void persistAndRetrievePipelineDeployment() {
        Tenant tenant = createAndSaveTenant("deploy-tenant-" + UUID.randomUUID().toString().substring(0, 8));

        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(tenant.getTenantId());
        pipeline.setName("Deployment Test Pipeline");
        pipeline.setSqlQuery("SELECT 1");
        pipeline.setStatus(PipelineStatus.RUNNING);
        pipeline.setParallelism(1);
        pipeline.setCheckpointIntervalMs(60000L);
        pipeline.setUpgradeMode(UpgradeMode.STATELESS);

        PipelineDeployment deployment = new PipelineDeployment();
        deployment.setPipeline(pipeline);
        deployment.setVersion(1);
        deployment.setK8sResourceName("tenant-slug-pipeline-name");
        deployment.setFlinkJobId("flink-job-abc123");
        deployment.setLifecycleState("RUNNING");
        deployment.setJobState(JobState.RUNNING);
        pipeline.setDeployment(deployment);

        pipelineRepository.save(pipeline);
        entityManager.flush();
        entityManager.clear();

        Pipeline loadedPipeline = pipelineRepository.findById(pipeline.getPipelineId()).orElseThrow();
        PipelineDeployment loadedDeployment = pipelineDeploymentRepository
                .findById(loadedPipeline.getPipelineId()).orElseThrow();

        assertThat(loadedDeployment.getK8sResourceName()).isEqualTo("tenant-slug-pipeline-name");
        assertThat(loadedDeployment.getFlinkJobId()).isEqualTo("flink-job-abc123");
        assertThat(loadedDeployment.getLifecycleState()).isEqualTo("RUNNING");
        assertThat(loadedDeployment.getJobState()).isEqualTo(JobState.RUNNING);
    }

    private Tenant createAndSaveTenant(String slug) {
        Tenant tenant = new Tenant();
        tenant.setSlug(slug);
        tenant.setName("Test Tenant");
        tenant.setContactEmail("test@example.com");
        tenant.setFid("fid-" + slug);
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenantRepository.save(tenant);
    }
}
