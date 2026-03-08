package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.domain.Tenant;
import com.flinkaidlc.platform.domain.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response returned on successful tenant onboarding.
 * Extends the standard TenantResponse with FID credentials shown once only —
 * the fidSecret is never stored in the database.
 */
public record OnboardTenantResponse(
    UUID tenantId,
    String slug,
    String name,
    String contactEmail,
    TenantStatus status,
    int maxPipelines,
    int maxTotalParallelism,
    long usedPipelines,
    long usedParallelism,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String fid,
    String fidSecret,
    boolean namespaceProvisioned
) {
    public static OnboardTenantResponse from(Tenant tenant, String fidSecret) {
        return new OnboardTenantResponse(
            tenant.getTenantId(),
            tenant.getSlug(),
            tenant.getName(),
            tenant.getContactEmail(),
            tenant.getStatus(),
            tenant.getMaxPipelines(),
            tenant.getMaxTotalParallelism(),
            0L,
            0L,
            tenant.getCreatedAt(),
            tenant.getUpdatedAt(),
            tenant.getFid(),
            fidSecret,
            true
        );
    }
}
