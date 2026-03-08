package com.flinkaidlc.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory IP-based rate limiter for the unauthenticated tenant registration endpoint.
 *
 * <p>Limits each IP to {@code registration.rate-limit.requests-per-window} requests per
 * {@code registration.rate-limit.window-seconds} sliding window. The counter resets after
 * the window has elapsed since the first request from that IP.
 *
 * <p>In production, replace or supplement this with a Redis-backed or API-gateway limiter
 * to handle horizontal scaling.
 */
@Component
public class TenantRegistrationRateLimiter implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistrationRateLimiter.class);

    @Value("${registration.rate-limit.requests-per-window:5}")
    private int maxRequestsPerWindow;

    @Value("${registration.rate-limit.window-seconds:60}")
    private long windowSeconds;

    // key: clientIp, value: (windowStart epoch seconds, counter)
    private final ConcurrentHashMap<String, long[]> ipCounters = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {

        String ip = resolveClientIp(request);
        long now = Instant.now().getEpochSecond();

        long[] entry = ipCounters.compute(ip, (key, existing) -> {
            if (existing == null || now - existing[0] >= windowSeconds) {
                // New window: [windowStart, count=1]
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });

        if (entry[1] > maxRequestsPerWindow) {
            log.warn("Rate limit exceeded for IP={} count={} window={}s", ip, entry[1], windowSeconds);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\"," +
                "\"status\":429,\"detail\":\"Registration rate limit exceeded. Try again later.\"}"
            );
            return false;
        }

        return true;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take only the first IP — do not trust the full chain without gateway validation
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
