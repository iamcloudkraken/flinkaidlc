package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.Pipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@code FlinkDeployment} CRD manifest as a nested {@code Map} structure
 * suitable for submission via Fabric8's {@code GenericKubernetesResource} API.
 *
 * <p>The generated manifest targets the Flink Kubernetes Operator
 * {@code flink.apache.org/v1beta1} API version.
 */
@Component
public class FlinkDeploymentBuilder {

    static final String FLINK_API_VERSION = "flink.apache.org/v1beta1";
    static final String FLINK_KIND = "FlinkDeployment";
    static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    static final String MANAGED_BY_VALUE = "flink-platform";

    @Value("${flink.image:flink:1.20}")
    private String flinkImage;

    @Value("${flink.state.s3-bucket:flink-state}")
    private String s3Bucket;

    @Value("${kubernetes.namespace-prefix:tenant-}")
    private String namespacePrefix;

    /**
     * Builds the FlinkDeployment manifest map for a pipeline.
     *
     * @param pipeline   the pipeline domain object
     * @param tenantSlug the tenant's slug (used to derive the K8s namespace)
     * @return a Map representing the full FlinkDeployment YAML structure
     */
    public Map<String, Object> build(Pipeline pipeline, String tenantSlug) {
        String pipelineId = pipeline.getPipelineId().toString();
        String tenantId = pipeline.getTenantId().toString();
        String namespace = namespacePrefix + tenantSlug;
        String resourceName = "pipeline-" + pipelineId;
        String configMapName = "pipeline-sql-" + pipelineId;

        Map<String, Object> manifest = new HashMap<>();
        manifest.put("apiVersion", FLINK_API_VERSION);
        manifest.put("kind", FLINK_KIND);
        manifest.put("metadata", buildMetadata(resourceName, namespace, tenantId, pipelineId));
        manifest.put("spec", buildSpec(pipeline, pipelineId, tenantId, configMapName));
        return manifest;
    }

    private Map<String, Object> buildMetadata(String name, String namespace, String tenantId, String pipelineId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        metadata.put("labels", Map.of(
            MANAGED_BY_LABEL, MANAGED_BY_VALUE,
            "tenant-id", tenantId,
            "pipeline-id", pipelineId
        ));
        return metadata;
    }

    private Map<String, Object> buildSpec(Pipeline pipeline, String pipelineId, String tenantId, String configMapName) {
        String checkpointsDir = "s3://" + s3Bucket + "/" + tenantId + "/" + pipelineId + "/checkpoints";
        String savepointsDir = "s3://" + s3Bucket + "/" + tenantId + "/" + pipelineId + "/savepoints";

        Map<String, Object> flinkConfig = new HashMap<>();
        flinkConfig.put("taskmanager.numberOfTaskSlots", "1");
        flinkConfig.put("state.backend", "rocksdb");
        flinkConfig.put("state.backend.incremental", "true");
        flinkConfig.put("state.checkpoints.dir", checkpointsDir);
        flinkConfig.put("state.savepoints.dir", savepointsDir);
        flinkConfig.put("execution.checkpointing.interval", pipeline.getCheckpointIntervalMs() + "ms");
        flinkConfig.put("restart-strategy", "fixed-delay");
        flinkConfig.put("restart-strategy.fixed-delay.attempts", "3");

        Map<String, Object> jobManagerResource = new HashMap<>();
        jobManagerResource.put("memory", "1024m");
        jobManagerResource.put("cpu", 0.5);

        Map<String, Object> taskManagerResource = new HashMap<>();
        taskManagerResource.put("memory", "1024m");
        taskManagerResource.put("cpu", 1.0);

        Map<String, Object> job = new HashMap<>();
        job.put("jarURI", "local:///opt/flink/usrlib/sql-runner.jar");
        job.put("args", List.of("/opt/flink/usrlib/sql-scripts/statements.sql"));
        job.put("parallelism", pipeline.getParallelism());
        job.put("upgradeMode", upgradeModeValue(pipeline.getUpgradeMode().name()));
        job.put("state", "running");

        // Pod template — mount the SQL ConfigMap
        Map<String, Object> podTemplate = buildPodTemplate(configMapName);

        Map<String, Object> spec = new HashMap<>();
        spec.put("image", flinkImage);
        spec.put("flinkVersion", "v1_20");
        spec.put("flinkConfiguration", flinkConfig);
        spec.put("serviceAccount", "flink");
        spec.put("jobManager", Map.of("resource", jobManagerResource));
        spec.put("taskManager", Map.of("resource", taskManagerResource));
        spec.put("job", job);
        spec.put("podTemplate", podTemplate);
        return spec;
    }

    private Map<String, Object> buildPodTemplate(String configMapName) {
        Map<String, Object> volumeMount = new HashMap<>();
        volumeMount.put("name", "sql-scripts");
        volumeMount.put("mountPath", "/opt/flink/usrlib/sql-scripts");

        Map<String, Object> container = new HashMap<>();
        container.put("name", "flink-main-container");
        container.put("volumeMounts", List.of(volumeMount));

        Map<String, Object> configMapVolumeSource = new HashMap<>();
        configMapVolumeSource.put("name", configMapName);

        Map<String, Object> volume = new HashMap<>();
        volume.put("name", "sql-scripts");
        volume.put("configMap", configMapVolumeSource);

        Map<String, Object> podSpec = new HashMap<>();
        podSpec.put("volumes", List.of(volume));
        podSpec.put("containers", List.of(container));

        return Map.of("spec", podSpec);
    }

    static String upgradeModeValue(String enumName) {
        return switch (enumName) {
            case "SAVEPOINT" -> "savepoint";
            case "LAST_STATE" -> "last-state";
            case "STATELESS" -> "stateless";
            default -> "savepoint";
        };
    }
}
