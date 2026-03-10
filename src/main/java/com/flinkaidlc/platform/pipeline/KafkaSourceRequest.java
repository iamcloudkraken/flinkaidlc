package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.StartupMode;
import jakarta.validation.constraints.NotBlank;

public record KafkaSourceRequest(
        @NotBlank String tableName,
        @NotBlank String topic,
        @NotBlank String bootstrapServers,
        @NotBlank String consumerGroup,
        StartupMode startupMode,
        @NotBlank String schemaRegistryUrl,
        @NotBlank String avroSubject,
        String watermarkColumn,
        long watermarkDelayMs
) implements PipelineSourceRequest {
    public KafkaSourceRequest {
        if (startupMode == null) {
            startupMode = StartupMode.GROUP_OFFSETS;
        }
    }
}
