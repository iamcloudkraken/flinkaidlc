package com.flinkaidlc.platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Base64;

/**
 * Local development security configuration.
 *
 * <p>Provides a pass-through {@link JwtDecoder} that skips signature validation
 * (avoiding issuer URI mismatch between browser-facing Keycloak URL and in-cluster URL)
 * but reads the real {@code tenant_id} claim from the token payload.
 * Falls back to the demo tenant ID for opaque tokens (e.g. {@code Bearer dev-token}).
 *
 * <p><strong>NEVER active in production</strong> — guarded by {@code @Profile("local")}.
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    /** Fallback tenant ID for opaque/raw dev tokens (e.g. curl -H "Bearer dev-token"). */
    public static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pass-through JWT decoder: skips signature verification but extracts real claims.
     * Reads {@code tenant_id} from the actual Keycloak JWT payload so multi-tenant
     * local dev works correctly without requiring issuer URI configuration.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            String tenantId = LOCAL_TENANT_ID;
            String sub = "local-dev-user";
            try {
                String[] parts = token.split("\\.");
                if (parts.length == 3) {
                    byte[] payloadBytes = Base64.getUrlDecoder().decode(
                        parts[1].length() % 4 == 0 ? parts[1]
                            : parts[1] + "=".repeat(4 - parts[1].length() % 4)
                    );
                    JsonNode payload = MAPPER.readTree(payloadBytes);
                    if (payload.has("tenant_id")) {
                        tenantId = payload.get("tenant_id").asText(LOCAL_TENANT_ID);
                    }
                    if (payload.has("sub")) {
                        sub = payload.get("sub").asText("local-dev-user");
                    }
                }
            } catch (Exception ignored) {
                // Not a JWT — fall back to demo tenant
            }
            return Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", sub)
                .claim("tenant_id", tenantId)
                .claim("scope", "openid")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        };
    }
}
