package com.flinkaidlc.platform.config;

import com.flinkaidlc.platform.security.TenantJwtAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security filter chain configuration.
 *
 * <p>Self-service tenant registration ({@code POST /api/v1/tenants}) is permitted without
 * authentication to support onboarding flows. In production deployments this endpoint
 * MUST be placed behind an API-gateway rate limiter and, optionally, disabled entirely
 * via {@code registration.enabled=false} when self-service sign-up is not desired
 * (e.g., enterprise-only deployments where tenants are provisioned by an internal admin
 * tool).
 *
 * <p>Rate limiting at the application layer (bucket4j / Spring's built-in limiter) is
 * intentionally deferred to unit-02 where the TenantService is introduced; placing it
 * here would create a dependency on in-memory or Redis state that is out of scope for
 * the data-model-and-foundation unit.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * When {@code false}, the unauthenticated {@code POST /api/v1/tenants} path is
     * removed from the permit-list and returns 403/404, effectively disabling
     * self-service tenant registration. Defaults to {@code true}.
     */
    @Value("${registration.enabled:true}")
    private boolean registrationEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    TenantJwtAuthenticationConverter converter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> {
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/tenants").permitAll();
                if (registrationEnabled) {
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/tenants").permitAll();
                }
                authorize
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    // Deny everything else — defence-in-depth against unanticipated
                    // endpoint exposure (e.g., accidental actuator wildcard expansion).
                    .anyRequest().denyAll();
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
            );

        return http.build();
    }
}
