package com.jachai.ai.service;

import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiQuotaService {
    private static final Duration KEY_LIFETIME = Duration.ofDays(2);
    private final StringRedisTemplate redis;

    public void consume(String organizationId, String userId) {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        try {
            long organizationUses = increment("jachai:ai:org:" + organizationId + ":" + day);
            long userUses = increment("jachai:ai:user:" + organizationId + ":" + userId + ":" + day);
            if (organizationUses > 200 || userUses > 25) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED",
                        "Daily AI request limit reached");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_QUOTA_UNAVAILABLE",
                    "AI usage control is unavailable");
        }
    }

    private long increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("Redis did not return a counter value");
        }
        redis.expire(key, KEY_LIFETIME);
        return count;
    }
}
