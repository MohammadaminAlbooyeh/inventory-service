package com.inventory.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationLockService {

    private static final String LOCK_KEY_PREFIX = "lock:reservation:";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentMap<String, String> lockTokens = new ConcurrentHashMap<>();

    @Value("${lock.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${lock.base-backoff-ms:20}")
    private long baseBackoffMs = 20;

    @Value("${lock.max-backoff-ms:500}")
    private long maxBackoffMs = 500;

    /**
     * Acquires the lock, retrying with exponential backoff and jitter instead of failing
     * immediately on contention. Returns {@code false} only after all attempts are spent.
     */
    public boolean acquire(String resourceId) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (tryLock(resourceId)) {
                return true;
            }
            if (attempt == maxAttempts) {
                break;
            }
            long exponential = baseBackoffMs * (1L << Math.min(attempt - 1, 16));
            long backoff = Math.min(maxBackoffMs, exponential);
            long jitter = ThreadLocalRandom.current().nextLong(baseBackoffMs + 1);
            try {
                Thread.sleep(backoff + jitter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("Lock contention on {} - gave up after {} attempts", resourceId, maxAttempts);
        return false;
    }

    public boolean tryLock(String resourceId) {
        return tryLock(resourceId, DEFAULT_TTL);
    }

    public boolean tryLock(String resourceId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String key = LOCK_KEY_PREFIX + resourceId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            lockTokens.put(key, token);
            return true;
        }
        return false;
    }

    public void unlock(String resourceId) {
        String key = LOCK_KEY_PREFIX + resourceId;
        String token = lockTokens.get(key);
        if (token == null) {
            return;
        }
        String current = redisTemplate.opsForValue().get(key);
        if (token.equals(current)) {
            redisTemplate.delete(key);
        }
        lockTokens.remove(key);
    }
}