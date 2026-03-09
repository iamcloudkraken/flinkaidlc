package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.Pipeline;

import java.util.UUID;

public interface FlinkOrchestrationService {
    void deploy(Pipeline pipeline);
    void upgrade(Pipeline pipeline);
    void suspend(Pipeline pipeline);
    void resume(Pipeline pipeline);
    void teardown(Pipeline pipeline);
    void suspendAll(UUID tenantId);
}
