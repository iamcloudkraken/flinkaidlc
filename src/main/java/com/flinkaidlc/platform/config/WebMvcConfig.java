package com.flinkaidlc.platform.config;

import com.flinkaidlc.platform.ratelimit.TenantRegistrationRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the tenant registration rate-limiter interceptor on the unauthenticated
 * POST /api/v1/tenants path only.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantRegistrationRateLimiter rateLimiter;

    @Value("${registration.enabled:true}")
    private boolean registrationEnabled;

    public WebMvcConfig(TenantRegistrationRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (registrationEnabled) {
            registry.addInterceptor(rateLimiter)
                .addPathPatterns("/api/v1/tenants")
                // Only intercept POST (the unauthenticated registration endpoint)
                .order(1);
        }
    }
}
