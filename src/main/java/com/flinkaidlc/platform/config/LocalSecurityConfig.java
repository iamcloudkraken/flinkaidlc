package com.flinkaidlc.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

/**
 * Local development security configuration.
 *
 * <p>Provides a mock {@link JwtDecoder} that accepts any Bearer token value and returns
 * a synthetic JWT with a fixed {@code tenant_id} claim matching the seed demo tenant.
 *
 * <p><strong>NEVER active in production</strong> — guarded by {@code @Profile("local")}.
 * The fixed {@code tenant_id} value matches the demo tenant created by {@code LocalDataSeeder}.
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    /** Fixed tenant ID matching the seed demo tenant (created by LocalDataSeeder). */
    public static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    /**
     * Mock JWT decoder that accepts any bearer token and injects local dev claims.
     * Use {@code Authorization: Bearer dev-token} (or any string) for local curl requests.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
            .header("alg", "none")
            .claim("sub", "local-dev-user")
            .claim("tenant_id", LOCAL_TENANT_ID)
            .claim("scope", "openid")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    }
}
