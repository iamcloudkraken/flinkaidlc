package com.flinkaidlc.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TenantJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Value("${jwt.tenant-id-claim:tenant_id}")
    private String tenantIdClaim = "tenant_id";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String tenantIdStr = jwt.getClaimAsString(tenantIdClaim);
        if (tenantIdStr == null) {
            throw new BadCredentialsException("JWT missing tenant_id claim");
        }
        UUID tenantId = UUID.fromString(tenantIdStr);
        TenantAuthenticationPrincipal principal = new TenantAuthenticationPrincipal(tenantId);
        return new TenantJwtAuthenticationToken(jwt, principal);
    }
}
