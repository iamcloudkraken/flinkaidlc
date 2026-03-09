package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.JobState;
import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineDeployment;
import com.flinkaidlc.platform.domain.Tenant;
import com.flinkaidlc.platform.repository.PipelineDeploymentRepository;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kubernetes-backed implementation of {@link FlinkOrchestrationService}.
 *
 * <p>All K8s calls are explicitly namespaced using the tenant's slug to prevent
 * cross-tenant namespace access. The namespace is always derived as
 * {@code {namespace-prefix}{tenantSlug}} — never taken from user input directly.
 */
@Service
@Primary
@ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "true", matchIfMissing = true)
public class FlinkOrchestrationServiceImpl implements FlinkOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(FlinkOrchestrationServiceImpl.class);

    private static final String FLINK_API_GROUP = "flink.apache.org";
    private static final String FLINK_API_VERSION = "v1beta1";
    private static final String FLINK_KIND_PLURAL = "flinkdeployments";

    private final KubernetesClient k8sClient;
    private final FlinkSqlGenerator sqlGenerator;
    private final FlinkDeploymentBuilder deploymentBuilder;
    private final TenantRepository tenantRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineDeploymentRepository deploymentRepository;
    private final String namespacePrefix;

    public FlinkOrchestrationServiceImpl(
        KubernetesClient k8sClient,
        FlinkSqlGenerator sqlGenerator,
        FlinkDeploymentBuilder deploymentBuilder,
        TenantRepository tenantRepository,
        PipelineRepository pipelineRepository,
        PipelineDeploymentRepository deploymentRepository,
        @Value("${kubernetes.namespace-prefix:tenant-}") String namespacePrefix
    ) {
        this.k8sClient = k8sClient;
        this.sqlGenerator = sqlGenerator;
        this.deploymentBuilder = deploymentBuilder;
        this.tenantRepository = tenantRepository;
        this.pipelineRepository = pipelineRepository;
        this.deploymentRepository = deploymentRepository;
        this.namespacePrefix = namespacePrefix;
    }

    @Override
    public void deploy(Pipeline pipeline) {
        String tenantSlug = resolveTenantSlug(pipeline.getTenantId());
        String namespace = namespacePrefix + tenantSlug;
        String pipelineId = pipeline.getPipelineId().toString();
        String configMapName = "pipeline-sql-" + pipelineId;
        String resourceName = "pipeline-" + pipelineId;

        log.info("Deploying pipeline {} to namespace {}", pipelineId, namespace);

        // 1. Generate SQL and create ConfigMap
        String sql = sqlGenerator.generate(pipeline);
        createOrUpdateConfigMap(namespace, configMapName, pipelineId, sql);

        // 2. Create FlinkDeployment CRD
        Map<String, Object> crdSpec = deploymentBuilder.build(pipeline, tenantSlug);
        createOrReplaceFlinkDeployment(namespace, crdSpec);

        // 3. Upsert PipelineDeployment record
        PipelineDeployment deployment = deploymentRepository.findById(pipeline.getPipelineId())
            .orElseGet(() -> {
                PipelineDeployment d = new PipelineDeployment();
                d.setPipeline(pipeline);
                return d;
            });
        deployment.setK8sResourceName(resourceName);
        deployment.setConfigmapName(configMapName);
        deployment.setLifecycleState("DEPLOYED");
        deployment.setLastSyncedAt(OffsetDateTime.now());
        deploymentRepository.save(deployment);

        log.info("Pipeline {} deployed: configmap={} crd={}", pipelineId, configMapName, resourceName);
    }

    @Override
    public void upgrade(Pipeline pipeline) {
        String tenantSlug = resolveTenantSlug(pipeline.getTenantId());
        String namespace = namespacePrefix + tenantSlug;
        String pipelineId = pipeline.getPipelineId().toString();
        String configMapName = "pipeline-sql-" + pipelineId;

        log.info("Upgrading pipeline {} in namespace {}", pipelineId, namespace);

        // 1. Regenerate SQL and update ConfigMap
        String sql = sqlGenerator.generate(pipeline);
        createOrUpdateConfigMap(namespace, configMapName, pipelineId, sql);

        // 2. Patch FlinkDeployment
        Map<String, Object> crdSpec = deploymentBuilder.build(pipeline, tenantSlug);
        createOrReplaceFlinkDeployment(namespace, crdSpec);

        // 3. Increment version
        deploymentRepository.findById(pipeline.getPipelineId()).ifPresent(d -> {
            d.setVersion(d.getVersion() + 1);
            d.setLastSyncedAt(OffsetDateTime.now());
            deploymentRepository.save(d);
        });

        log.info("Pipeline {} upgraded", pipelineId);
    }

    @Override
    public void suspend(Pipeline pipeline) {
        String tenantSlug = resolveTenantSlug(pipeline.getTenantId());
        String namespace = namespacePrefix + tenantSlug;
        String pipelineId = pipeline.getPipelineId().toString();
        String resourceName = "pipeline-" + pipelineId;

        log.info("Suspending pipeline {} in namespace {}", pipelineId, namespace);
        patchJobState(namespace, resourceName, "suspended");

        deploymentRepository.findById(pipeline.getPipelineId()).ifPresent(d -> {
            d.setJobState(JobState.SUSPENDED);
            d.setLastSyncedAt(OffsetDateTime.now());
            deploymentRepository.save(d);
        });
    }

    @Override
    public void resume(Pipeline pipeline) {
        String tenantSlug = resolveTenantSlug(pipeline.getTenantId());
        String namespace = namespacePrefix + tenantSlug;
        String pipelineId = pipeline.getPipelineId().toString();
        String resourceName = "pipeline-" + pipelineId;

        log.info("Resuming pipeline {} in namespace {}", pipelineId, namespace);
        patchJobState(namespace, resourceName, "running");

        deploymentRepository.findById(pipeline.getPipelineId()).ifPresent(d -> {
            d.setJobState(null);
            d.setLastSyncedAt(OffsetDateTime.now());
            deploymentRepository.save(d);
        });
    }

    @Override
    public void teardown(Pipeline pipeline) {
        String tenantSlug = resolveTenantSlug(pipeline.getTenantId());
        String namespace = namespacePrefix + tenantSlug;
        String pipelineId = pipeline.getPipelineId().toString();
        String resourceName = "pipeline-" + pipelineId;
        String configMapName = "pipeline-sql-" + pipelineId;

        log.info("Tearing down pipeline {} in namespace {}", pipelineId, namespace);

        // 1. Patch to suspended to trigger savepoint
        try {
            patchJobState(namespace, resourceName, "suspended");
        } catch (Exception e) {
            log.warn("Failed to suspend pipeline {} before teardown: {}", pipelineId, e.getMessage());
        }

        // 2. Delete FlinkDeployment CRD
        try {
            k8sClient.genericKubernetesResources(FLINK_API_GROUP + "/" + FLINK_API_VERSION, FLINK_KIND_PLURAL)
                .inNamespace(namespace)
                .withName(resourceName)
                .delete();
            log.info("Deleted FlinkDeployment {}/{}", namespace, resourceName);
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404) {
                log.error("Failed to delete FlinkDeployment {}/{}: {}", namespace, resourceName, e.getMessage());
            }
        }

        // 3. Delete ConfigMap
        try {
            k8sClient.configMaps().inNamespace(namespace).withName(configMapName).delete();
            log.info("Deleted ConfigMap {}/{}", namespace, configMapName);
        } catch (Exception e) {
            log.error("Failed to delete ConfigMap {}/{}: {}", namespace, configMapName, e.getMessage());
        }

        log.info("Teardown complete for pipeline {}", pipelineId);
    }

    @Override
    public void suspendAll(UUID tenantId) {
        String tenantSlug = resolveTenantSlug(tenantId);
        String namespace = namespacePrefix + tenantSlug;

        log.info("Suspending all FlinkDeployments in namespace {} for tenant {}", namespace, tenantId);

        try {
            var deployments = k8sClient
                .genericKubernetesResources(FLINK_API_GROUP + "/" + FLINK_API_VERSION, FLINK_KIND_PLURAL)
                .inNamespace(namespace)
                .withLabel(FlinkDeploymentBuilder.MANAGED_BY_LABEL, FlinkDeploymentBuilder.MANAGED_BY_VALUE)
                .withLabel("tenant-id", tenantId.toString())
                .list()
                .getItems();

            for (var deployment : deployments) {
                String name = deployment.getMetadata().getName();
                try {
                    patchJobState(namespace, name, "suspended");
                    log.info("Suspended FlinkDeployment {}/{}", namespace, name);
                } catch (Exception e) {
                    log.error("Failed to suspend FlinkDeployment {}/{}: {}", namespace, name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to list FlinkDeployments in namespace {}: {}", namespace, e.getMessage(), e);
        }
    }

    // ---- private helpers ----

    private void createOrUpdateConfigMap(String namespace, String name, String pipelineId, String sql) {
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(FlinkDeploymentBuilder.MANAGED_BY_LABEL, FlinkDeploymentBuilder.MANAGED_BY_VALUE)
                .addToLabels("pipeline-id", pipelineId)
            .endMetadata()
            .withData(Map.of("statements.sql", sql))
            .build();

        k8sClient.configMaps().inNamespace(namespace).resource(configMap).createOrReplace();
        log.debug("ConfigMap {}/{} created/updated", namespace, name);
    }

    @SuppressWarnings("unchecked")
    private void createOrReplaceFlinkDeployment(String namespace, Map<String, Object> manifest) {
        var resource = k8sClient
            .genericKubernetesResources(FLINK_API_GROUP + "/" + FLINK_API_VERSION, FLINK_KIND_PLURAL)
            .inNamespace(namespace);

        // Build GenericKubernetesResource from raw map
        io.fabric8.kubernetes.api.model.GenericKubernetesResource gkr =
            new io.fabric8.kubernetes.api.model.GenericKubernetesResource();
        gkr.setApiVersion(FLINK_API_GROUP + "/" + FLINK_API_VERSION);
        gkr.setKind(FlinkDeploymentBuilder.FLINK_KIND);

        io.fabric8.kubernetes.api.model.ObjectMeta meta = new io.fabric8.kubernetes.api.model.ObjectMeta();
        Map<String, Object> metaMap = (Map<String, Object>) manifest.get("metadata");
        meta.setName((String) metaMap.get("name"));
        meta.setNamespace(namespace);
        meta.setLabels((Map<String, String>) metaMap.get("labels"));
        gkr.setMetadata(meta);

        gkr.setAdditionalProperty("spec", manifest.get("spec"));

        resource.resource(gkr).createOrReplace();
        log.debug("FlinkDeployment created/replaced in namespace {}", namespace);
    }

    private void patchJobState(String namespace, String resourceName, String jobState) {
        Map<String, Object> patch = Map.of(
            "spec", Map.of(
                "job", Map.of("state", jobState)
            )
        );

        k8sClient
            .genericKubernetesResources(FLINK_API_GROUP + "/" + FLINK_API_VERSION, FLINK_KIND_PLURAL)
            .inNamespace(namespace)
            .withName(resourceName)
            .patch(io.fabric8.kubernetes.client.utils.Serialization.asJson(patch));

        log.debug("Patched FlinkDeployment {}/{} job.state={}", namespace, resourceName, jobState);
    }

    /**
     * Resolves the tenant slug for a given tenant ID.
     * Throws if tenant not found — guards against orphaned pipeline operations.
     */
    private String resolveTenantSlug(UUID tenantId) {
        return tenantRepository.findById(tenantId)
            .map(Tenant::getSlug)
            .orElseThrow(() -> new IllegalStateException("Tenant not found for ID: " + tenantId));
    }
}
