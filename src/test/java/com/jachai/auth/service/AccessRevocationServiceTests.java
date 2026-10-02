package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.RevokedAccessToken;
import com.jachai.auth.repository.RevokedAccessTokenRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AccessRevocationServiceTests {
    @Mock RevokedAccessTokenRepository revoked;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    @Test
    void persistsRevocationBeforeCaching() {
        when(redis.opsForValue()).thenReturn(values);
        new AccessRevocationService(revoked, redis, true)
                .revoke("jti-1", Instant.now().plusSeconds(300));

        verify(revoked).save(any(RevokedAccessToken.class));
        verify(values).set(eq("jachai:revoked-access:jti-1"), eq("1"), any(Duration.class));
    }

    @Test
    void mongoRemainsAuthoritativeWhenRedisIsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(revoked.existsById("jti-1")).thenReturn(true);

        assertTrue(new AccessRevocationService(revoked, redis, true).isRevoked("jti-1"));
        verify(revoked).existsById("jti-1");
    }

    @Test
    void redisFailureFallsBackToMongo() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis offline"));
        when(revoked.existsById("jti-1")).thenReturn(true);

        assertTrue(new AccessRevocationService(revoked, redis, true).isRevoked("jti-1"));
    }

    @Test
    void mongoFailureIsNotTreatedAsValidToken() {
        when(revoked.existsById("jti-1")).thenThrow(new IllegalStateException("Mongo offline"));

        assertThrows(IllegalStateException.class,
                () -> new AccessRevocationService(revoked, redis, false).isRevoked("jti-1"));
    }

    @Test
    void localModeChecksMongoWithoutRedis() {
        assertFalse(new AccessRevocationService(revoked, redis, false).isRevoked("jti-1"));
        verify(revoked).existsById("jti-1");
        verify(redis, never()).opsForValue();
    }
}
