package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.domain.PipelineStatus;
import com.flinkaidlc.platform.security.TenantAuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping
    public ResponseEntity<PipelineResponse> createPipeline(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @RequestBody @Valid CreatePipelineRequest request) {
        PipelineResponse response = pipelineService.createPipeline(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PipelinePageResponse> listPipelines(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @RequestParam(required = false) PipelineStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PipelinePageResponse response = pipelineService.listPipelines(principal.tenantId(), status, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{pipelineId}")
    public ResponseEntity<PipelineDetailResponse> getPipelineDetail(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @PathVariable UUID pipelineId) {
        PipelineDetailResponse response = pipelineService.getPipelineDetail(principal.tenantId(), pipelineId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{pipelineId}")
    public ResponseEntity<PipelineResponse> updatePipeline(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @PathVariable UUID pipelineId,
            @RequestBody @Valid UpdatePipelineRequest request) {
        PipelineResponse response = pipelineService.updatePipeline(principal.tenantId(), pipelineId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{pipelineId}")
    public ResponseEntity<Void> deletePipeline(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @PathVariable UUID pipelineId) {
        pipelineService.deletePipeline(principal.tenantId(), pipelineId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pipelineId}/suspend")
    public ResponseEntity<PipelineResponse> suspendPipeline(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @PathVariable UUID pipelineId) {
        PipelineResponse response = pipelineService.suspendPipeline(principal.tenantId(), pipelineId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{pipelineId}/resume")
    public ResponseEntity<PipelineResponse> resumePipeline(
            @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
            @PathVariable UUID pipelineId) {
        PipelineResponse response = pipelineService.resumePipeline(principal.tenantId(), pipelineId);
        return ResponseEntity.ok(response);
    }
}
