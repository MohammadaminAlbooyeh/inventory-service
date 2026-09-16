package com.inventory.lock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationLockServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ReservationLockService lockService;

    @Test
    void tryLockAcquiresLockWhenAvailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        boolean acquired = lockService.tryLock("product:p1");

        assertThat(acquired).isTrue();
        verify(valueOperations).setIfAbsent(eq("lock:reservation:product:p1"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void tryLockFailsWhenAlreadyLocked() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);

        boolean acquired = lockService.tryLock("product:p1");

        assertThat(acquired).isFalse();
    }

    @Test
    void tryLockWithCustomTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        boolean acquired = lockService.tryLock("product:p1", java.time.Duration.ofSeconds(60));

        assertThat(acquired).isTrue();
        verify(valueOperations).setIfAbsent(eq("lock:reservation:product:p1"), anyString(), eq(java.time.Duration.ofSeconds(60)));
    }

    @Test
    void unlockReleasesLock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String[] capturedToken = new String[1];
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenAnswer(invocation -> {
            capturedToken[0] = invocation.getArgument(1);
            return true;
        });

        lockService.tryLock("product:p1");

        when(valueOperations.get("lock:reservation:product:p1")).thenReturn(capturedToken[0]);
        lockService.unlock("product:p1");

        verify(redisTemplate).delete("lock:reservation:product:p1");
    }

    @Test
    void acquireRetriesThenSucceeds() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(false, false, true);

        boolean acquired = lockService.acquire("product:p1");

        assertThat(acquired).isTrue();
        verify(valueOperations, times(3))
                .setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void acquireGivesUpAfterMaxAttempts() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(false);

        boolean acquired = lockService.acquire("product:p1");

        assertThat(acquired).isFalse();
        verify(valueOperations, times(5))
                .setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void unlockDoesNothingWhenNotLocked() {
        lockService.unlock("product:p1");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void unlockDoesNotDeleteWhenTokenMismatch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        lockService.tryLock("product:p1");

        when(valueOperations.get("lock:reservation:product:p1")).thenReturn("different-token");
        lockService.unlock("product:p1");

        verify(redisTemplate, never()).delete(anyString());
    }
}
