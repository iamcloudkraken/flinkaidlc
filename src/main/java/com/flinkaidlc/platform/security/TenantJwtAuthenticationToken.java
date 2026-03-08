package com.flinkaidlc.platform.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collections;

/**
 * Custom JwtAuthenticationToken that exposes TenantAuthenticationPrincipal
 * as the authentication principal, making it accessible via @AuthenticationPrincipal.
 */
public class TenantJwtAuthenticationToken extends JwtAuthenticationToken {

    private final TenantAuthenticationPrincipal tenantPrincipal;

    public TenantJwtAuthenticationToken(Jwt jwt, TenantAuthenticationPrincipal tenantPrincipal) {
        super(jwt, Collections.emptyList(), tenantPrincipal.tenantId().toString());
        this.tenantPrincipal = tenantPrincipal;
    }

    @Override
    public Object getPrincipal() {
        return tenantPrincipal;
    }
}
