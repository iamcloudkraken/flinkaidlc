package com.flinkaidlc.platform.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Test configuration that replaces the real JwtDecoder with a mock one
 * that accepts any token and returns a Jwt with a test tenant_id claim.
 */
@TestConfiguration
public class TestSecurityConfig {

    public static final UUID TEST_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Bean
    @Primary
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("test-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_id", TEST_TENANT_ID.toString())
                .claims(claims -> claims.putAll(Map.of(
                        "iss", "http://localhost:9999/auth/realms/test"
                )))
                .build();
    }
}
