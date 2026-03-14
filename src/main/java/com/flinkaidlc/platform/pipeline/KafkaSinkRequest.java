package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.ColumnDefinition;
import com.flinkaidlc.platform.domain.DeliveryGuarantee;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record KafkaSinkRequest(
        @NotBlank String tableName,
        @NotBlank String topic,
        @NotBlank String bootstrapServers,
        @NotBlank String schemaRegistryUrl,
        @NotBlank String avroSubject,
        DeliveryGuarantee deliveryGuarantee,
        List<ColumnDefinition> columns
) implements PipelineSinkRequest {
    public KafkaSinkRequest {
        if (deliveryGuarantee == null) {
            deliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE;
        }
    }
}
