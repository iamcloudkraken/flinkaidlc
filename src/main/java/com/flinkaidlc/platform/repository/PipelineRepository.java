package com.flinkaidlc.platform.repository;

import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByTenantId(UUID tenantId);
    Page<Pipeline> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Pipeline> findByTenantIdAndStatus(UUID tenantId, PipelineStatus status, Pageable pageable);
    long countByTenantIdAndStatusNot(UUID tenantId, PipelineStatus status);

    /**
     * Counts active (non-DELETED, non-DRAFT) pipelines for a tenant.
     */
    long countByTenantIdAndStatus(UUID tenantId, PipelineStatus status);

    /**
     * Sums the parallelism of all active (non-DELETED) pipelines for a tenant.
     * Returns 0 if no matching pipelines exist.
     */
    @Query("SELECT COALESCE(SUM(p.parallelism), 0) FROM Pipeline p WHERE p.tenantId = :tenantId AND p.status NOT IN :excludedStatuses")
    long sumParallelismByTenantIdExcludingStatuses(
        @Param("tenantId") UUID tenantId,
        @Param("excludedStatuses") List<PipelineStatus> excludedStatuses
    );
}
