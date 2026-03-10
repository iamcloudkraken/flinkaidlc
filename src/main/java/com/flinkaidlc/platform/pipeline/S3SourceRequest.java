package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.ColumnDefinition;
import com.flinkaidlc.platform.domain.S3AuthType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record S3SourceRequest(
        @NotBlank String tableName,
        @NotBlank String bucket,
        String prefix,
        boolean partitioned,
        S3AuthType authType,
        String accessKey,
        String secretKey,
        List<ColumnDefinition> columns
) implements PipelineSourceRequest {

    @AssertTrue(message = "accessKey and secretKey are required when authType is ACCESS_KEY")
    public boolean isCredentialsValid() {
        if (authType == S3AuthType.ACCESS_KEY) {
            return accessKey != null && !accessKey.isBlank()
                    && secretKey != null && !secretKey.isBlank();
        }
        return true;
    }
}
