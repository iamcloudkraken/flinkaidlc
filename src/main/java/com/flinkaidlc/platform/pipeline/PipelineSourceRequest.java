package com.flinkaidlc.platform.pipeline;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = KafkaSourceRequest.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = KafkaSourceRequest.class, name = "KAFKA"),
    @JsonSubTypes.Type(value = S3SourceRequest.class, name = "S3")
})
public sealed interface PipelineSourceRequest permits KafkaSourceRequest, S3SourceRequest {
    String tableName();
}
