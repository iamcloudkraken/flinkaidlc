package com.flinkaidlc.platform.k8s;

import com.flinkaidlc.platform.exception.KubernetesConflictException;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.networking.v1.*;
import io.fabric8.kubernetes.api.model.rbac.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Provisions and tears down the per-tenant Kubernetes namespace and associated RBAC/quota resources.
 *
 * <p>Resources created on onboarding (in order):
 * <ol>
 *   <li>Namespace {@code tenant-<slug>}</li>
 *   <li>ServiceAccount {@code flink}</li>
 *   <li>Role {@code flink} with pod/configmap/deployment permissions</li>
 *   <li>RoleBinding binding the Role to the ServiceAccount</li>
 *   <li>ResourceQuota {@code tenant-quota} derived from tenant limits</li>
 *   <li>NetworkPolicy {@code tenant-isolation} denying cross-namespace ingress</li>
 * </ol>
 */
@Service
public class TenantNamespaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantNamespaceProvisioner.class);

    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "flink-platform";
    private static final String FLINK_SA = "flink";
    private static final String QUOTA_NAME = "tenant-quota";
    private static final String NETPOL_NAME = "tenant-isolation";

    private final KubernetesClient k8sClient;
    private final String namespacePrefix;
    private final int podsPerPipeline;

    // The platform's own namespace — ingress from here is allowed
    private final String platformNamespace;

    public TenantNamespaceProvisioner(
        KubernetesClient k8sClient,
        @Value("${kubernetes.namespace-prefix:tenant-}") String namespacePrefix,
        @Value("${flink.pods-per-pipeline:4}") int podsPerPipeline,
        @Value("${kubernetes.platform-namespace:flink-platform}") String platformNamespace
    ) {
        this.k8sClient = k8sClient;
        this.namespacePrefix = namespacePrefix;
        this.podsPerPipeline = podsPerPipeline;
        this.platformNamespace = platformNamespace;
    }

    /**
     * Derives the K8s namespace name for a tenant slug.
     */
    public String namespaceName(String slug) {
        return namespacePrefix + slug;
    }

    /**
     * Provisions all K8s resources for a new tenant.
     *
     * @throws KubernetesConflictException if the namespace already exists
     * @throws RuntimeException            wrapping any other K8s API error
     */
    public void provision(String slug, int maxPipelines, int maxTotalParallelism) {
        String ns = namespaceName(slug);
        log.info("Provisioning K8s namespace {} for tenant slug={}", ns, slug);

        try {
            createNamespace(ns, slug);
            createServiceAccount(ns);
            createRole(ns);
            createRoleBinding(ns);
            createResourceQuota(ns, maxPipelines, maxTotalParallelism);
            createNetworkPolicy(ns);
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                throw new KubernetesConflictException("Kubernetes namespace already exists: " + ns, e);
            }
            throw e;
        }

        log.info("Provisioning complete for namespace {}", ns);
    }

    /**
     * Deletes the tenant's namespace (cascades all resources within it).
     * Deletion is async in K8s — this method returns immediately after the API call.
     */
    public void deprovision(String slug) {
        String ns = namespaceName(slug);
        log.info("Deprovisioning K8s namespace {} for tenant slug={}", ns, slug);
        try {
            k8sClient.namespaces().withName(ns).delete();
            log.info("Delete request sent for namespace {}", ns);
        } catch (Exception e) {
            log.error("Failed to delete namespace {}: {}", ns, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Patches the ResourceQuota in the tenant namespace with updated limits.
     */
    public void patchResourceQuota(String slug, int maxPipelines, int maxTotalParallelism) {
        String ns = namespaceName(slug);
        log.info("Patching ResourceQuota in namespace {} maxPipelines={} maxTotalParallelism={}", ns, maxPipelines, maxTotalParallelism);

        ResourceQuota quota = buildResourceQuota(ns, maxPipelines, maxTotalParallelism);
        k8sClient.resourceQuotas()
            .inNamespace(ns)
            .withName(QUOTA_NAME)
            .createOrReplace(quota);

        log.info("ResourceQuota patched in namespace {}", ns);
    }

    // ---- private builders ----

    private void createNamespace(String ns, String slug) {
        Namespace namespace = new NamespaceBuilder()
            .withNewMetadata()
                .withName(ns)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .addToLabels("tenant-slug", slug)
            .endMetadata()
            .build();
        k8sClient.namespaces().resource(namespace).create();
        log.debug("Created namespace {}", ns);
    }

    private void createServiceAccount(String ns) {
        ServiceAccount sa = new ServiceAccountBuilder()
            .withNewMetadata()
                .withName(FLINK_SA)
                .withNamespace(ns)
            .endMetadata()
            .build();
        k8sClient.serviceAccounts().inNamespace(ns).resource(sa).create();
        log.debug("Created ServiceAccount {}/{}", ns, FLINK_SA);
    }

    private void createRole(String ns) {
        Role role = new RoleBuilder()
            .withNewMetadata()
                .withName(FLINK_SA)
                .withNamespace(ns)
            .endMetadata()
            .withRules(
                new PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("pods", "configmaps", "services", "endpoints")
                    .withVerbs("get", "list", "watch", "create", "update", "delete")
                    .build(),
                new PolicyRuleBuilder()
                    .withApiGroups("apps")
                    .withResources("deployments", "replicasets")
                    .withVerbs("get", "list", "watch", "create", "update", "delete")
                    .build()
            )
            .build();
        k8sClient.rbac().roles().inNamespace(ns).resource(role).create();
        log.debug("Created Role {}/{}", ns, FLINK_SA);
    }

    private void createRoleBinding(String ns) {
        RoleBinding rb = new RoleBindingBuilder()
            .withNewMetadata()
                .withName(FLINK_SA)
                .withNamespace(ns)
            .endMetadata()
            .withNewRoleRef()
                .withApiGroup("rbac.authorization.k8s.io")
                .withKind("Role")
                .withName(FLINK_SA)
            .endRoleRef()
            .withSubjects(
                new SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(FLINK_SA)
                    .withNamespace(ns)
                    .build()
            )
            .build();
        k8sClient.rbac().roleBindings().inNamespace(ns).resource(rb).create();
        log.debug("Created RoleBinding {}/{}", ns, FLINK_SA);
    }

    private void createResourceQuota(String ns, int maxPipelines, int maxTotalParallelism) {
        ResourceQuota quota = buildResourceQuota(ns, maxPipelines, maxTotalParallelism);
        k8sClient.resourceQuotas().inNamespace(ns).resource(quota).create();
        log.debug("Created ResourceQuota {}/{}", ns, QUOTA_NAME);
    }

    private ResourceQuota buildResourceQuota(String ns, int maxPipelines, int maxTotalParallelism) {
        int podLimit = maxPipelines * podsPerPipeline;
        String memoryLimit = (maxTotalParallelism * 2) + "Gi";

        return new ResourceQuotaBuilder()
            .withNewMetadata()
                .withName(QUOTA_NAME)
                .withNamespace(ns)
            .endMetadata()
            .withNewSpec()
                .withHard(Map.of(
                    "pods", new Quantity(String.valueOf(podLimit)),
                    "requests.cpu", new Quantity(String.valueOf(maxTotalParallelism)),
                    "requests.memory", new Quantity(memoryLimit)
                ))
            .endSpec()
            .build();
    }

    private void createNetworkPolicy(String ns) {
        // Allow ingress only from:
        // 1. Same namespace (tenant's own pods)
        // 2. Platform namespace (platform operator)
        // All other cross-namespace ingress is denied by the default deny rule with explicit allows.
        NetworkPolicy netpol = new NetworkPolicyBuilder()
            .withNewMetadata()
                .withName(NETPOL_NAME)
                .withNamespace(ns)
            .endMetadata()
            .withNewSpec()
                // Apply to all pods in this namespace
                .withNewPodSelector()
                .endPodSelector()
                .withPolicyTypes("Ingress")
                .withIngress(
                    // Allow from same namespace
                    new NetworkPolicyIngressRuleBuilder()
                        .withFrom(
                            new NetworkPolicyPeerBuilder()
                                .withNewPodSelector()
                                .endPodSelector()
                                .build()
                        )
                        .build(),
                    // Allow from platform namespace
                    new NetworkPolicyIngressRuleBuilder()
                        .withFrom(
                            new NetworkPolicyPeerBuilder()
                                .withNewNamespaceSelector()
                                    .withMatchLabels(Map.of("kubernetes.io/metadata.name", platformNamespace))
                                .endNamespaceSelector()
                                .build()
                        )
                        .build()
                )
            .endSpec()
            .build();
        k8sClient.network().networkPolicies().inNamespace(ns).resource(netpol).create();
        log.debug("Created NetworkPolicy {}/{}", ns, NETPOL_NAME);
    }
}
