package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineStatus;
import com.flinkaidlc.platform.domain.Tenant;
import com.flinkaidlc.platform.domain.TenantStatus;
import com.flinkaidlc.platform.exception.KubernetesConflictException;
import com.flinkaidlc.platform.exception.TenantNotFoundException;
import com.flinkaidlc.platform.exception.TenantProvisioningException;
import com.flinkaidlc.platform.exception.SlugAlreadyInUseException;
import com.flinkaidlc.platform.k8s.TenantNamespaceProvisioner;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderClient;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderException;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates tenant lifecycle: registration, retrieval, update, and deletion.
 *
 * <p>Onboarding flow:
 * <ol>
 *   <li>Validate slug uniqueness</li>
 *   <li>Persist Tenant record</li>
 *   <li>Provision K8s namespace</li>
 *   <li>Register FID with OAuth2 provider</li>
 * </ol>
 *
 * <p>On any failure after DB persist, compensating deletes are attempted and
 * {@link TenantProvisioningException} is thrown (mapped to HTTP 503).
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private static final List<PipelineStatus> INACTIVE_STATUSES =
        List.of(PipelineStatus.DELETED, PipelineStatus.DRAFT);

    private final TenantRepository tenantRepository;
    private final PipelineRepository pipelineRepository;
    private final TenantNamespaceProvisioner provisioner;
    private final OAuth2ProviderClient oauth2Client;

    public TenantService(
        TenantRepository tenantRepository,
        PipelineRepository pipelineRepository,
        TenantNamespaceProvisioner provisioner,
        OAuth2ProviderClient oauth2Client
    ) {
        this.tenantRepository = tenantRepository;
        this.pipelineRepository = pipelineRepository;
        this.provisioner = provisioner;
        this.oauth2Client = oauth2Client;
    }

    /**
     * Self-service tenant onboarding.
     *
     * @return the response containing the FID and fidSecret (shown once)
     * @throws SlugAlreadyInUseException   if the slug is taken
     * @throws TenantProvisioningException if K8s or OAuth2 provisioning fails
     */
    @Transactional
    public OnboardTenantResponse onboardTenant(OnboardTenantRequest request) {
        // 1. Validate slug uniqueness
        if (tenantRepository.findBySlug(request.slug()).isPresent()) {
            throw new SlugAlreadyInUseException(request.slug());
        }

        // 2. Generate FID (UUID string) and a one-time secret
        String fid = UUID.randomUUID().toString();
        String fidSecret = UUID.randomUUID().toString();

        // 3. Persist tenant record
        Tenant tenant = new Tenant();
        tenant.setSlug(request.slug());
        tenant.setName(request.name());
        tenant.setContactEmail(request.contactEmail());
        tenant.setFid(fid);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.updateQuota(request.maxPipelines(), request.maxTotalParallelism());
        tenant = tenantRepository.save(tenant);

        UUID tenantId = tenant.getTenantId();
        log.info("Persisted tenant tenantId={} slug={}", tenantId, request.slug());

        // 4. Provision K8s namespace — compensate on failure
        try {
            provisioner.provision(request.slug(), request.maxPipelines(), request.maxTotalParallelism());
        } catch (KubernetesConflictException e) {
            log.error("K8s namespace conflict for slug={}: {}", request.slug(), e.getMessage());
            tenantRepository.delete(tenant);
            throw new TenantProvisioningException("Kubernetes namespace conflict for slug: " + request.slug(), e);
        } catch (Exception e) {
            log.error("K8s provisioning failed for slug={}: {}", request.slug(), e.getMessage(), e);
            tenantRepository.delete(tenant);
            throw new TenantProvisioningException("Kubernetes provisioning failed for slug: " + request.slug(), e);
        }

        // 5. Register FID with OAuth2 provider — compensate on failure
        try {
            oauth2Client.registerClient(fid, fidSecret);
        } catch (OAuth2ProviderException e) {
            log.error("OAuth2 registration failed for fid={}: {}", fid, e.getMessage(), e);
            // Compensating deletes (best-effort)
            try {
                provisioner.deprovision(request.slug());
            } catch (Exception deprovisionEx) {
                log.error("Compensating deprovision also failed for slug={}: {}", request.slug(), deprovisionEx.getMessage(), deprovisionEx);
            }
            tenantRepository.delete(tenant);
            throw new TenantProvisioningException("OAuth2 provider unavailable during tenant registration", e);
        } catch (Exception e) {
            log.error("Unexpected OAuth2 error for fid={}: {}", fid, e.getMessage(), e);
            try {
                provisioner.deprovision(request.slug());
            } catch (Exception deprovisionEx) {
                log.error("Compensating deprovision failed for slug={}: {}", request.slug(), deprovisionEx.getMessage(), deprovisionEx);
            }
            tenantRepository.delete(tenant);
            throw new TenantProvisioningException("OAuth2 registration failed unexpectedly", e);
        }

        log.info("Tenant onboarding complete tenantId={} slug={} fid={}", tenantId, request.slug(), fid);
        return OnboardTenantResponse.from(tenant, fidSecret);
    }

    /**
     * Returns tenant metadata plus current resource usage counts.
     *
     * @throws TenantNotFoundException if the tenant does not exist
     */
    public TenantResponse getTenantWithUsage(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException(tenantId));

        long usedPipelines = pipelineRepository.countByTenantIdAndStatusNot(tenantId, PipelineStatus.DELETED);
        long usedParallelism = pipelineRepository.sumParallelismByTenantIdExcludingStatuses(tenantId, INACTIVE_STATUSES);

        return TenantResponse.from(tenant, usedPipelines, usedParallelism);
    }

    /**
     * Updates tenant metadata and (when quota changes) patches the K8s ResourceQuota.
     *
     * @throws TenantNotFoundException if the tenant does not exist
     */
    @Transactional
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException(tenantId));

        boolean quotaChanged = tenant.getMaxPipelines() != request.maxPipelines()
            || tenant.getMaxTotalParallelism() != request.maxTotalParallelism();

        tenant.setName(request.name());
        tenant.setContactEmail(request.contactEmail());
        tenant.updateQuota(request.maxPipelines(), request.maxTotalParallelism());
        tenant = tenantRepository.save(tenant);

        if (quotaChanged) {
            provisioner.patchResourceQuota(tenant.getSlug(), request.maxPipelines(), request.maxTotalParallelism());
        }

        long usedPipelines = pipelineRepository.countByTenantIdAndStatusNot(tenantId, PipelineStatus.DELETED);
        long usedParallelism = pipelineRepository.sumParallelismByTenantIdExcludingStatuses(tenantId, INACTIVE_STATUSES);

        return TenantResponse.from(tenant, usedPipelines, usedParallelism);
    }

    /**
     * Deletes a tenant: suspends pipelines (stub), removes K8s namespace, marks DELETED.
     *
     * @throws TenantNotFoundException if the tenant does not exist
     */
    @Transactional
    public void deleteTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException(tenantId));

        // Stub: will be implemented fully in unit-03
        suspendAll(tenantId);

        // Delete K8s namespace (cascades all resources; async in K8s)
        provisioner.deprovision(tenant.getSlug());

        // Mark tenant DELETED
        tenant.setStatus(TenantStatus.DELETED);
        tenantRepository.save(tenant);

        log.info("Tenant deleted tenantId={} slug={}", tenantId, tenant.getSlug());
    }

    /**
     * Stub for suspending all running pipelines belonging to a tenant.
     * Will be fully implemented in unit-03 when PipelineService is introduced.
     */
    public void suspendAll(UUID tenantId) {
        log.warn("suspendAll called for tenantId={} — no-op stub (unit-03 will implement)", tenantId);
    }
}
