package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @UuidGenerator
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "slug", unique = true, nullable = false, length = 63)
    private String slug;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "fid", unique = true, nullable = false)
    private String fid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status = TenantStatus.ACTIVE;

    @Setter(AccessLevel.NONE)
    @Column(name = "max_pipelines", nullable = false)
    private int maxPipelines = 10;

    @Setter(AccessLevel.NONE)
    @Column(name = "max_total_parallelism", nullable = false)
    private int maxTotalParallelism = 50;

    /**
     * Updates the tenant's resource quota. Callers must hold admin/billing authority;
     * this method is the only sanctioned mutation point for quota fields.
     */
    public void updateQuota(int maxPipelines, int maxTotalParallelism) {
        if (maxPipelines < 1 || maxTotalParallelism < 1) {
            throw new IllegalArgumentException("Quota values must be positive");
        }
        this.maxPipelines = maxPipelines;
        this.maxTotalParallelism = maxTotalParallelism;
    }

    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
