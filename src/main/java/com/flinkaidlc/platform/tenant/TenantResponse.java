package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.domain.Tenant;
import com.flinkaidlc.platform.domain.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Standard tenant response including resource usage counters.
 */
public record TenantResponse(
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
    OffsetDateTime updatedAt
) {
    public static TenantResponse from(Tenant tenant, long usedPipelines, long usedParallelism) {
        return new TenantResponse(
            tenant.getTenantId(),
            tenant.getSlug(),
            tenant.getName(),
            tenant.getContactEmail(),
            tenant.getStatus(),
            tenant.getMaxPipelines(),
            tenant.getMaxTotalParallelism(),
            usedPipelines,
            usedParallelism,
            tenant.getCreatedAt(),
            tenant.getUpdatedAt()
        );
    }
}
