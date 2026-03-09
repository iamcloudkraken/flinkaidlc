package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.DeliveryGuarantee;
import jakarta.validation.constraints.NotBlank;

public record PipelineSinkRequest(
        @NotBlank String tableName,
        @NotBlank String topic,
        @NotBlank String bootstrapServers,
        @NotBlank String schemaRegistryUrl,
        @NotBlank String avroSubject,
        DeliveryGuarantee deliveryGuarantee
) {
    public PipelineSinkRequest {
        if (deliveryGuarantee == null) {
            deliveryGuarantee = DeliveryGuarantee.AT_LEAST_ONCE;
        }
    }
}
