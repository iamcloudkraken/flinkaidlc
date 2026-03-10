package com.flinkaidlc.platform.pipeline;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = KafkaSinkRequest.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = KafkaSinkRequest.class, name = "KAFKA"),
    @JsonSubTypes.Type(value = S3SinkRequest.class, name = "S3")
})
public sealed interface PipelineSinkRequest permits KafkaSinkRequest, S3SinkRequest {
    String tableName();
}
