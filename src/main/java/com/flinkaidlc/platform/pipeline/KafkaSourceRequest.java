package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.ColumnDefinition;
import com.flinkaidlc.platform.domain.StartupMode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record KafkaSourceRequest(
        @NotBlank String tableName,
        @NotBlank String topic,
        @NotBlank String bootstrapServers,
        @NotBlank String consumerGroup,
        StartupMode startupMode,
        @NotBlank String schemaRegistryUrl,
        @NotBlank String avroSubject,
        String watermarkColumn,
        long watermarkDelayMs,
        List<ColumnDefinition> columns
) implements PipelineSourceRequest {
    public KafkaSourceRequest {
        if (startupMode == null) {
            startupMode = StartupMode.GROUP_OFFSETS;
        }
    }
}
