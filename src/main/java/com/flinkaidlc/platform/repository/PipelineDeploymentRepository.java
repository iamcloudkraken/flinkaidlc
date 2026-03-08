package com.flinkaidlc.platform.repository;

import com.flinkaidlc.platform.domain.PipelineDeployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PipelineDeploymentRepository extends JpaRepository<PipelineDeployment, UUID> {
    // No custom methods needed for this unit
}
