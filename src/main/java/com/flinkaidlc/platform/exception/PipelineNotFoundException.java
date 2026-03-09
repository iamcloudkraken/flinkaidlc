package com.flinkaidlc.platform.exception;

import java.util.UUID;

public class PipelineNotFoundException extends RuntimeException {

    public PipelineNotFoundException(UUID pipelineId) {
        super("Pipeline not found: " + pipelineId);
    }
}
