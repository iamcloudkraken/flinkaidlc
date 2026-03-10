package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PipelineDetailResponse(
        UUID pipelineId,
        UUID tenantId,
        String name,
        String description,
        String sqlQuery,
        int parallelism,
        long checkpointIntervalMs,
        UpgradeMode upgradeMode,
        PipelineStatus status,
        List<PipelineSource> sources,
        List<PipelineSink> sinks,
        // Deployment state
        String lifecycleState,
        JobState jobState,
        String lastSavepointPath,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static PipelineDetailResponse from(Pipeline pipeline, List<PipelineDeployment> deployments) {
        PipelineDeployment deployment = deployments.isEmpty() ? null : deployments.get(0);
        return new PipelineDetailResponse(
                pipeline.getPipelineId(),
                pipeline.getTenantId(),
                pipeline.getName(),
                pipeline.getDescription(),
                pipeline.getSqlQuery(),
                pipeline.getParallelism(),
                pipeline.getCheckpointIntervalMs(),
                pipeline.getUpgradeMode(),
                pipeline.getStatus(),
                new ArrayList<>(pipeline.getSources()),
                new ArrayList<>(pipeline.getSinks()),
                deployment != null ? deployment.getLifecycleState() : null,
                deployment != null ? deployment.getJobState() : null,
                deployment != null ? deployment.getLastSavepointPath() : null,
                deployment != null ? deployment.getErrorMessage() : null,
                pipeline.getCreatedAt(),
                pipeline.getUpdatedAt()
        );
    }
}
