package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnMissingBean(FlinkOrchestrationService.class)
public class NoOpFlinkOrchestrationService implements FlinkOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(NoOpFlinkOrchestrationService.class);

    @Override
    public void deploy(Pipeline pipeline) {
        log.info("[NoOp] deploy called for pipeline={}", pipeline.getPipelineId());
    }

    @Override
    public void upgrade(Pipeline pipeline) {
        log.info("[NoOp] upgrade called for pipeline={}", pipeline.getPipelineId());
    }

    @Override
    public void suspend(Pipeline pipeline) {
        log.info("[NoOp] suspend called for pipeline={}", pipeline.getPipelineId());
    }

    @Override
    public void resume(Pipeline pipeline) {
        log.info("[NoOp] resume called for pipeline={}", pipeline.getPipelineId());
    }

    @Override
    public void teardown(Pipeline pipeline) {
        log.info("[NoOp] teardown called for pipeline={}", pipeline.getPipelineId());
    }

    @Override
    public void suspendAll(UUID tenantId) {
        log.info("[NoOp] suspendAll called for tenantId={}", tenantId);
    }
}
