package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.UpgradeMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record CreatePipelineRequest(
        @NotBlank String name,
        String description,
        @NotBlank String sqlQuery,
        @Min(1) @Max(256) int parallelism,
        @Min(1000) long checkpointIntervalMs,
        @NotNull UpgradeMode upgradeMode,
        @NotNull @Size(min = 1) @Valid List<PipelineSourceRequest> sources,
        @NotNull @Size(min = 1) @Valid List<PipelineSinkRequest> sinks
) {
}
