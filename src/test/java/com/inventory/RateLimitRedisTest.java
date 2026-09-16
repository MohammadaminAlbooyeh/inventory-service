package com.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the Redis-backed distributed rate limiter: requests past the window budget
 * get {@code 429}. Skipped when Redis is not reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EmbeddedKafka(partitions = 1)
@TestPropertySource(properties = {
        "ratelimit.enabled=true",
        "ratelimit.requests-per-window=3",
        "ratelimit.window-ms=60000"
})
class RateLimitRedisTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void redisAvailable() {
        try {
            redisTemplate.hasKey("__ping__:" + UUID.randomUUID());
        } catch (RuntimeException e) {
            assumeTrue(false, "Redis not available: " + e.getMessage());
        }
    }

    @Test
    void returns429AfterBudgetExhausted() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "203.0.113." + (1 + (int) (Math.random() * 250)));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        int ok = 0;
        int throttled = 0;
        for (int i = 0; i < 6; i++) {
            HttpStatus status = (HttpStatus) rest
                    .exchange("/api/inventory/items", HttpMethod.GET, request, String.class)
                    .getStatusCode();
            if (status == HttpStatus.OK) {
                ok++;
            } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
                throttled++;
            }
        }
        assertThat(ok).isEqualTo(3);
        assertThat(throttled).isEqualTo(3);
    }
}
