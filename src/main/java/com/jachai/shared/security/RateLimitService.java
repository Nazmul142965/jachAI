package com.jachai.shared.security;

import com.jachai.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private static final String SCRIPT_TEXT = "local count = redis.call('INCR', KEYS[1]); "
            + "if count == 1 or redis.call('TTL', KEYS[1]) < 0 then "
            + "redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return count";
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(SCRIPT_TEXT, Long.class);

    private final StringRedisTemplate redis;
    private final SecretKeySpec key;
    private final boolean enabled;

    public RateLimitService(StringRedisTemplate redis, @Value("${app.jwt.secret}") String secret,
                            @Value("${app.security.rate-limit.enabled:false}") boolean enabled) {
        this.redis = redis;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.enabled = enabled;
    }

    public void consume(RateLimitPolicy policy, String scope) {
        if (!enabled) return;
        String redisKey = "jachai:rl:v1:" + policy.name() + ":" + digest(scope);
        Long count;
        try {
            count = redis.execute(SCRIPT, Collections.singletonList(redisKey),
                    Long.toString(policy.window().toSeconds()));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE",
                    "Request protection is temporarily unavailable");
        }
        if (count == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE",
                    "Request protection is temporarily unavailable");
        }
        if (count > policy.limit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests; try again later");
        }
    }

    private String digest(String scope) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
