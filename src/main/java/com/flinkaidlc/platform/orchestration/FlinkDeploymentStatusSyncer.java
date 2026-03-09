package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.JobState;
import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineDeployment;
import com.flinkaidlc.platform.domain.PipelineStatus;
import com.flinkaidlc.platform.repository.PipelineDeploymentRepository;
import com.flinkaidlc.platform.repository.PipelineRepository;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watches FlinkDeployment CRDs across all tenant namespaces and syncs their status
 * into the {@code pipeline_deployments} and {@code pipelines} tables.
 *
 * <p>Two sync mechanisms:
 * <ol>
 *   <li><b>Informer</b> — reacts to MODIFIED events in near-real-time</li>
 *   <li><b>Fallback poller</b> — runs every {@code flink.sync.poll-interval-ms} (default 30s)
 *       to catch any missed informer events during disconnects</li>
 * </ol>
 */
@Component
public class FlinkDeploymentStatusSyncer {

    private static final Logger log = LoggerFactory.getLogger(FlinkDeploymentStatusSyncer.class);

    private static final String FLINK_API_GROUP = "flink.apache.org";
    private static final String FLINK_API_VERSION_FULL = "flink.apache.org/v1beta1";
    private static final String FLINK_KIND_PLURAL = "flinkdeployments";

    private final KubernetesClient k8sClient;
    private final PipelineRepository pipelineRepository;
    private final PipelineDeploymentRepository deploymentRepository;

    @Value("${flink.sync.poll-interval-ms:30000}")
    private long pollIntervalMs;

    private SharedIndexInformer<GenericKubernetesResource> informer;

    public FlinkDeploymentStatusSyncer(
        KubernetesClient k8sClient,
        PipelineRepository pipelineRepository,
        PipelineDeploymentRepository deploymentRepository
    ) {
        this.k8sClient = k8sClient;
        this.pipelineRepository = pipelineRepository;
        this.deploymentRepository = deploymentRepository;
    }

    @PostConstruct
    public void startInformer() {
        try {
            // Fabric8 6.x: use genericKubernetesResources DSL — sharedIndexInformerForCustomResource removed
            informer = k8sClient
                .genericKubernetesResources(FLINK_API_VERSION_FULL, FLINK_KIND_PLURAL)
                .inAnyNamespace()
                .inform(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(GenericKubernetesResource resource) {
                        // No-op on add — status is empty at this point
                    }

                    @Override
                    public void onUpdate(GenericKubernetesResource oldResource, GenericKubernetesResource newResource) {
                        handleStatusUpdate(newResource);
                    }

                    @Override
                    public void onDelete(GenericKubernetesResource resource, boolean deletedFinalStateUnknown) {
                        // Deletion handled by teardown() — no DB update needed here
                    }
                }, 0L);

            log.info("FlinkDeployment informer started");
        } catch (Exception e) {
            log.warn("Failed to start FlinkDeployment informer (K8s may not be available in dev): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stopInformer() {
        if (informer != null) {
            try {
                informer.close();
            } catch (Exception e) {
                log.warn("Error stopping FlinkDeployment informer: {}", e.getMessage());
            }
        }
    }

    /**
     * Fallback poller — runs every {@code flink.sync.poll-interval-ms} to catch missed events.
     */
    @Scheduled(fixedDelayString = "${flink.sync.poll-interval-ms:30000}")
    public void pollAndSync() {
        log.debug("FlinkDeployment fallback poll starting");
        try {
            List<GenericKubernetesResource> deployments = k8sClient
                .genericKubernetesResources(FLINK_API_VERSION_FULL, FLINK_KIND_PLURAL)
                .inAnyNamespace()
                .withLabel(FlinkDeploymentBuilder.MANAGED_BY_LABEL, FlinkDeploymentBuilder.MANAGED_BY_VALUE)
                .list()
                .getItems();

            for (GenericKubernetesResource resource : deployments) {
                try {
                    handleStatusUpdate(resource);
                } catch (Exception e) {
                    log.error("Error syncing status for {}: {}",
                        resource.getMetadata().getName(), e.getMessage(), e);
                }
            }
            log.debug("FlinkDeployment fallback poll completed ({} items)", deployments.size());
        } catch (Exception e) {
            log.error("FlinkDeployment fallback poll failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void handleStatusUpdate(GenericKubernetesResource resource) {
        String resourceName = resource.getMetadata().getName();
        Map<String, String> labels = resource.getMetadata().getLabels();
        if (labels == null) return;

        String pipelineIdStr = labels.get("pipeline-id");
        if (pipelineIdStr == null) {
            log.debug("FlinkDeployment {} has no pipeline-id label, skipping", resourceName);
            return;
        }

        UUID pipelineId;
        try {
            pipelineId = UUID.fromString(pipelineIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid pipeline-id label value '{}' on FlinkDeployment {}", pipelineIdStr, resourceName);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) resource.getAdditionalProperties().get("status");
        if (status == null) {
            log.debug("FlinkDeployment {} has no status yet", resourceName);
            return;
        }

        String lifecycleState = (String) status.get("lifecycleState");
        String errorMessage = (String) status.get("error");

        @SuppressWarnings("unchecked")
        Map<String, Object> jobStatus = (Map<String, Object>) status.get("jobStatus");
        String jobStateName = jobStatus != null ? (String) jobStatus.get("state") : null;

        // Extract savepoint path from status
        String savepointPath = extractSavepointPath(status);

        // Determine Pipeline status from state mapping table
        PipelineStatus newPipelineStatus = mapToPipelineStatus(lifecycleState, jobStateName);

        Pipeline pipeline = pipelineRepository.findById(pipelineId).orElse(null);
        if (pipeline == null) {
            log.debug("Pipeline {} not found in DB for FlinkDeployment {}", pipelineId, resourceName);
            return;
        }

        // Update Pipeline status
        pipeline.setStatus(newPipelineStatus);
        pipelineRepository.save(pipeline);

        // Update PipelineDeployment
        PipelineDeployment deployment = deploymentRepository.findById(pipelineId).orElse(null);
        if (deployment != null) {
            if (lifecycleState != null) deployment.setLifecycleState(lifecycleState);
            if (jobStateName != null) deployment.setJobState(mapJobState(jobStateName));
            if (savepointPath != null) deployment.setLastSavepointPath(savepointPath);
            if (errorMessage != null) deployment.setErrorMessage(errorMessage);
            deployment.setLastSyncedAt(OffsetDateTime.now());
            deploymentRepository.save(deployment);
        }

        log.debug("Synced pipeline {} → status={} lifecycleState={} jobState={}",
            pipelineId, newPipelineStatus, lifecycleState, jobStateName);
    }

    /**
     * State mapping table per unit-04 spec:
     * <pre>
     * lifecycleState=DEPLOYED/UPGRADING + jobStatus=RUNNING → RUNNING
     * lifecycleState=DEPLOYED + jobStatus=FAILED            → FAILED
     * lifecycleState=SUSPENDED                              → SUSPENDED
     * lifecycleState=FAILED (operator level)                → FAILED
     * </pre>
     */
    private PipelineStatus mapToPipelineStatus(String lifecycleState, String jobStateName) {
        if (lifecycleState == null) return PipelineStatus.DEPLOYING;

        return switch (lifecycleState) {
            case "FAILED" -> PipelineStatus.FAILED;
            case "SUSPENDED" -> PipelineStatus.SUSPENDED;
            case "DEPLOYED", "UPGRADING" -> {
                if ("RUNNING".equalsIgnoreCase(jobStateName)) yield PipelineStatus.RUNNING;
                if ("FAILED".equalsIgnoreCase(jobStateName)) yield PipelineStatus.FAILED;
                yield PipelineStatus.DEPLOYING;
            }
            default -> PipelineStatus.DEPLOYING;
        };
    }

    private JobState mapJobState(String jobStateName) {
        if (jobStateName == null) return null;
        return switch (jobStateName.toUpperCase()) {
            case "RUNNING" -> JobState.RUNNING;
            case "FAILED" -> JobState.FAILED;
            case "SUSPENDED" -> JobState.SUSPENDED;
            case "FINISHED" -> JobState.FINISHED;
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private String extractSavepointPath(Map<String, Object> status) {
        try {
            Map<String, Object> jobStatus = (Map<String, Object>) status.get("jobStatus");
            if (jobStatus == null) return null;
            Map<String, Object> savepointInfo = (Map<String, Object>) jobStatus.get("savepointInfo");
            if (savepointInfo == null) return null;
            Map<String, Object> lastSavepoint = (Map<String, Object>) savepointInfo.get("lastSavepoint");
            if (lastSavepoint == null) return null;
            return (String) lastSavepoint.get("location");
        } catch (Exception e) {
            return null;
        }
    }
}
