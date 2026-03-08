package com.flinkaidlc.platform.config;

import com.flinkaidlc.platform.oauth2.KeycloakOAuth2ProviderClient;
import com.flinkaidlc.platform.oauth2.NoOpOAuth2ProviderClient;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Provides the active {@link OAuth2ProviderClient} bean.
 *
 * <p>When {@code oauth2.admin.url} is non-empty, the Keycloak implementation is used.
 * Otherwise, the no-op stub is activated (suitable for local dev and tests).
 */
@Configuration
public class OAuth2AdminConfig {

    /**
     * Real Keycloak implementation — activated when {@code oauth2.admin.url} is set to a non-empty value.
     */
    @Bean
    @ConditionalOnProperty(name = "oauth2.admin.url", matchIfMissing = false)
    public OAuth2ProviderClient keycloakOAuth2ProviderClient(
        @Value("${oauth2.admin.url}") String adminUrl,
        @Value("${oauth2.admin.realm}") String realm,
        @Value("${oauth2.admin.client-id}") String adminClientId,
        @Value("${oauth2.admin.client-secret}") String adminClientSecret,
        RestClient.Builder restClientBuilder
    ) {
        return new KeycloakOAuth2ProviderClient(adminUrl, realm, adminClientId, adminClientSecret, restClientBuilder);
    }

    /**
     * No-op fallback — activated when no Keycloak implementation is present.
     */
    @Bean
    @ConditionalOnMissingBean(OAuth2ProviderClient.class)
    public OAuth2ProviderClient noOpOAuth2ProviderClient() {
        return new NoOpOAuth2ProviderClient();
    }
}
