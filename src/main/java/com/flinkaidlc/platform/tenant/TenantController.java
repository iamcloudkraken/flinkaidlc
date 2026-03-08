package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.security.TenantAuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for tenant lifecycle management.
 *
 * <p>{@code POST /api/v1/tenants} is unauthenticated (self-service registration).
 * All other endpoints require a valid JWT with a matching {@code tenant_id} claim.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /**
     * Self-service tenant registration — unauthenticated.
     */
    @PostMapping
    public ResponseEntity<OnboardTenantResponse> registerTenant(
        @Valid @RequestBody OnboardTenantRequest request
    ) {
        OnboardTenantResponse response = tenantService.onboardTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Retrieve tenant metadata and resource usage — tenant-scoped.
     */
    @GetMapping("/{tenantId}")
    public ResponseEntity<TenantResponse> getTenant(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal TenantAuthenticationPrincipal principal
    ) {
        assertSameTenant(principal, tenantId);
        TenantResponse response = tenantService.getTenantWithUsage(tenantId);
        return ResponseEntity.ok(response);
    }

    /**
     * Update tenant metadata and resource limits — tenant-scoped.
     */
    @PutMapping("/{tenantId}")
    public ResponseEntity<TenantResponse> updateTenant(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal TenantAuthenticationPrincipal principal,
        @Valid @RequestBody UpdateTenantRequest request
    ) {
        assertSameTenant(principal, tenantId);
        TenantResponse response = tenantService.updateTenant(tenantId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete tenant — tenant-scoped. Returns 204 No Content.
     */
    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Void> deleteTenant(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal TenantAuthenticationPrincipal principal
    ) {
        assertSameTenant(principal, tenantId);
        tenantService.deleteTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Enforces tenant isolation: the JWT's tenant_id claim must match the path variable.
     *
     * @throws AccessDeniedException if the caller is accessing a different tenant's resource
     */
    private void assertSameTenant(TenantAuthenticationPrincipal principal, UUID requestedTenantId) {
        if (!principal.tenantId().equals(requestedTenantId)) {
            throw new AccessDeniedException(
                "Access denied: JWT tenant_id does not match the requested tenant"
            );
        }
    }
}
