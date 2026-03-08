package com.flinkaidlc.platform.repository;

import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PipelineRepository extends JpaRepository<Pipeline, UUID> {
    List<Pipeline> findByTenantId(UUID tenantId);
    long countByTenantIdAndStatusNot(UUID tenantId, PipelineStatus status);
}
