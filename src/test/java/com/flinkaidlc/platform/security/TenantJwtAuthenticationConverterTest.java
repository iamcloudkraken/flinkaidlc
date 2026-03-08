package com.flinkaidlc.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantJwtAuthenticationConverterTest {

    private final TenantJwtAuthenticationConverter converter = new TenantJwtAuthenticationConverter();

    @Test
    void convertJwtWithTenantIdClaimReturnsTenantPrincipal() {
        UUID tenantId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("test-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_id", tenantId.toString())
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token).isNotNull();
        assertThat(token.getPrincipal()).isInstanceOf(TenantAuthenticationPrincipal.class);
        TenantAuthenticationPrincipal principal = (TenantAuthenticationPrincipal) token.getPrincipal();
        assertThat(principal.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void convertJwtWithoutTenantIdClaimThrowsBadCredentials() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("test-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("tenant_id");
    }
}
