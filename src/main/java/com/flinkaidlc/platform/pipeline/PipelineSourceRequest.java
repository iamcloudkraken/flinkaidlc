package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.StartupMode;
import jakarta.validation.constraints.NotBlank;

public record PipelineSourceRequest(
        @NotBlank String tableName,
        @NotBlank String topic,
        @NotBlank String bootstrapServers,
        @NotBlank String consumerGroup,
        StartupMode startupMode,
        @NotBlank String schemaRegistryUrl,
        @NotBlank String avroSubject,
        String watermarkColumn,
        long watermarkDelayMs
) {
    public PipelineSourceRequest {
        if (startupMode == null) {
            startupMode = StartupMode.GROUP_OFFSETS;
        }
    }
}
