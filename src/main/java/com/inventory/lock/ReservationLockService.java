package com.inventory.lock;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class ReservationLockService {

    private static final String LOCK_KEY_PREFIX = "lock:reservation:";
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentMap<String, String> lockTokens = new ConcurrentHashMap<>();

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