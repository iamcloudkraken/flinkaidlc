package com.flinkaidlc.platform.k8s;

/**
 * Provisions and tears down per-tenant Kubernetes namespace resources.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link KubernetesTenantNamespaceProvisioner} — real K8s implementation (default)</li>
 *   <li>{@link NoOpTenantNamespaceProvisioner} — no-op for local dev ({@code k8s.provisioner.enabled=false})</li>
 * </ul>
 */
public interface ITenantNamespaceProvisioner {

    /**
     * Provisions all K8s resources for a new tenant.
     */
    void provision(String slug, int maxPipelines, int maxTotalParallelism);

    /**
     * Deletes the tenant's namespace (cascades all resources within it).
     */
    void deprovision(String slug);

    /**
     * Patches the ResourceQuota in the tenant namespace with updated limits.
     */
    void patchResourceQuota(String slug, int maxPipelines, int maxTotalParallelism);
}
