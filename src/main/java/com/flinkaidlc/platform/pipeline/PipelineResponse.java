package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.Pipeline;
import com.flinkaidlc.platform.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PipelineResponse(
        UUID pipelineId,
        UUID tenantId,
        String name,
        PipelineStatus status,
        int parallelism,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static PipelineResponse from(Pipeline pipeline) {
        return new PipelineResponse(
                pipeline.getPipelineId(),
                pipeline.getTenantId(),
                pipeline.getName(),
                pipeline.getStatus(),
                pipeline.getParallelism(),
                pipeline.getCreatedAt(),
                pipeline.getUpdatedAt()
        );
    }
}
