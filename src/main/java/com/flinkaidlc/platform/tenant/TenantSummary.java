package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.domain.Tenant;

import java.util.UUID;

/**
 * Minimal tenant projection returned by the public list endpoint.
 * Only exposes id and name — no sensitive fields.
 */
public record TenantSummary(UUID id, String name, String slug) {

    public static TenantSummary from(Tenant tenant) {
        return new TenantSummary(tenant.getTenantId(), tenant.getName(), tenant.getSlug());
    }
}
