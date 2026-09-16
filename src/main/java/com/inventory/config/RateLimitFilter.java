package com.inventory.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Distributed fixed-window rate limiter for {@code /api/**}, backed by Redis so the budget
 * is shared across every service instance. Fails open: if Redis is unreachable the request
 * is allowed and a warning is logged. Disable with {@code ratelimit.enabled=false}.
 */
@Slf4j
@Component
@Order(1)
@ConditionalOnProperty(prefix = "ratelimit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final int limit;
    private final long windowMs;

    public RateLimitFilter(StringRedisTemplate redisTemplate,
                           @Value("${ratelimit.requests-per-window:120}") int limit,
                           @Value("${ratelimit.window-ms:10000}") long windowMs) {
        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.windowMs = windowMs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isOverLimit(clientKey(request))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(Math.max(1, windowMs / 1000)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isOverLimit(String client) {
        long window = System.currentTimeMillis() / windowMs;
        String key = KEY_PREFIX + client + ':' + window;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMillis(windowMs * 2));
            }
            return count != null && count > limit;
        } catch (RuntimeException e) {
            log.warn("Rate limiter Redis error - allowing request (fail-open): {}", e.getMessage());
            return false;
        }
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
