package com.jachai.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.shared.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTests {
    @Mock StringRedisTemplate redis;
    private static final String SECRET = "test-only-rate-limit-secret-at-least-32-bytes";

    @Test
    void disabledLocalLimiterDoesNotCallRedis() {
        new RateLimitService(redis, SECRET, false).consume(RateLimitPolicy.LOGIN_IP, "192.0.2.1");
        verify(redis, never()).execute(any(RedisScript.class), any(List.class), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void firstRequestUsesHashedScopeAndAtomicScript() {
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(1L);

        new RateLimitService(redis, SECRET, true)
                .consume(RateLimitPolicy.LOGIN_ACCOUNT, "person@example.com");

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any());
        assertEquals(1, keys.getValue().size());
        assertFalse(keys.getValue().get(0).contains("person@example.com"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void excessRequestsReturn429() {
        when(redis.execute(any(RedisScript.class), any(List.class), any())).thenReturn(11L);

        ApiException error = assertThrows(ApiException.class,
                () -> new RateLimitService(redis, SECRET, true)
                        .consume(RateLimitPolicy.LOGIN_IP, "192.0.2.1"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        assertEquals("RATE_LIMITED", error.code());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unavailableRedisFailsClosed() {
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new IllegalStateException("Redis is down"));

        ApiException error = assertThrows(ApiException.class,
                () -> new RateLimitService(redis, SECRET, true)
                        .consume(RateLimitPolicy.RESET_IP, "192.0.2.1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("RATE_LIMIT_UNAVAILABLE", error.code());
    }
}
