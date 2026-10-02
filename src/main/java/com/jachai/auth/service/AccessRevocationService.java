package com.jachai.auth.service;

import com.jachai.auth.model.RevokedAccessToken;
import com.jachai.auth.repository.RevokedAccessTokenRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessRevocationService {
    private final RevokedAccessTokenRepository revoked;
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;

    public AccessRevocationService(RevokedAccessTokenRepository revoked, StringRedisTemplate redis,
                                   @Value("${app.security.redis-revocation.enabled:false}") boolean redisEnabled) {
        this.revoked = revoked;
        this.redis = redis;
        this.redisEnabled = redisEnabled;
    }

    public void revoke(String jti, Instant expiresAt) {
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) return;
        revoked.save(new RevokedAccessToken(jti, expiresAt));
        if (redisEnabled) {
            try {
                Duration remaining = Duration.between(Instant.now(), expiresAt);
                if (remaining.isPositive()) redis.opsForValue().set(key(jti), "1", remaining);
            } catch (RuntimeException ignored) {
                // MongoDB is durable truth; a Redis write failure cannot undo revocation.
            }
        }
    }

    public boolean isRevoked(String jti) {
        if (redisEnabled) {
            try {
                if ("1".equals(redis.opsForValue().get(key(jti)))) return true;
            } catch (RuntimeException ignored) {
                // Continue with MongoDB. If MongoDB is unavailable, authentication fails closed.
            }
        }
        return revoked.existsById(jti);
    }

    private String key(String jti) {
        return "jachai:revoked-access:" + jti;
    }
}
