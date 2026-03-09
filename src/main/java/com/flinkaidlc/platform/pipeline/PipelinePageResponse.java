package com.flinkaidlc.platform.pipeline;

import java.util.List;

public record PipelinePageResponse(
        List<PipelineResponse> items,
        int page,
        int size,
        long total
) {
}
