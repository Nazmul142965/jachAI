package com.jachai.ai.service;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.service.EntitlementService;
import com.jachai.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiQuotaService {
    private static final String SCRIPT_TEXT = "local org = tonumber(redis.call('GET', KEYS[1]) or '0'); "
            + "local user = tonumber(redis.call('GET', KEYS[2]) or '0'); "
            + "if org >= tonumber(ARGV[1]) or user >= tonumber(ARGV[2]) then return 0; end; "
            + "redis.call('INCR', KEYS[1]); redis.call('INCR', KEYS[2]); "
            + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]); end; "
            + "if redis.call('TTL', KEYS[2]) < 0 then redis.call('EXPIRE', KEYS[2], ARGV[3]); end; "
            + "return 1";
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(SCRIPT_TEXT, Long.class);
    private static final long KEY_LIFETIME_SECONDS = 2 * 24 * 60 * 60;
    private final StringRedisTemplate redis;
    private final EntitlementService entitlements;
    private final SecretKeySpec key;

    public AiQuotaService(StringRedisTemplate redis, EntitlementService entitlements,
                          @Value("${app.jwt.secret}") String secret) {
        this.redis = redis;
        this.entitlements = entitlements;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public void consume(String organizationId, String userId) {
        PlanEntitlements plan = entitlements.plan(organizationId);
        if (!plan.aiEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAN_FEATURE_UNAVAILABLE", "AI is not available on this plan");
        }
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        // Both keys share one Redis Cluster hash slot, without exposing tenant or user IDs.
        String slot = "{" + digest("ai:org:" + organizationId + ":" + day) + "}";
        String user = digest("ai:user:" + organizationId + ":" + day + ":" + userId);
        Long granted;
        try {
            granted = redis.execute(SCRIPT,
                    List.of("jachai:ai:v3:" + slot + ":org", "jachai:ai:v3:" + slot + ":user:" + user),
                    Integer.toString(plan.dailyOrganizationAiRequests()),
                    Integer.toString(plan.dailyUserAiRequests()), Long.toString(KEY_LIFETIME_SECONDS));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_QUOTA_UNAVAILABLE",
                    "AI usage control is unavailable");
        }
        if (granted == null || (granted != 0 && granted != 1)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_QUOTA_UNAVAILABLE",
                    "AI usage control is unavailable");
        }
        if (granted == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED",
                    "Daily AI request limit reached");
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
