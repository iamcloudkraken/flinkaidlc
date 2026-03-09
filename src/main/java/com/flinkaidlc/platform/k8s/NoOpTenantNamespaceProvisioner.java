package com.flinkaidlc.platform.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * No-op implementation of {@link ITenantNamespaceProvisioner} for local development.
 *
 * <p>Activated when {@code k8s.provisioner.enabled=false}. Logs all calls without
 * making any Kubernetes API requests, allowing the platform to run without a cluster.
 */
@Service
@ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "false")
public class NoOpTenantNamespaceProvisioner implements ITenantNamespaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(NoOpTenantNamespaceProvisioner.class);

    @Override
    public void provision(String slug, int maxPipelines, int maxTotalParallelism) {
        log.info("[local] Skipping K8s namespace provision for slug={} maxPipelines={} maxTotalParallelism={}",
            slug, maxPipelines, maxTotalParallelism);
    }

    @Override
    public void deprovision(String slug) {
        log.info("[local] Skipping K8s namespace deprovision for slug={}", slug);
    }

    @Override
    public void patchResourceQuota(String slug, int maxPipelines, int maxTotalParallelism) {
        log.info("[local] Skipping K8s resource quota patch for slug={}", slug);
    }
}
