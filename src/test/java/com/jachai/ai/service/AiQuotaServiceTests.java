package com.jachai.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.service.EntitlementService;
import com.jachai.shared.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTests {
    @Mock StringRedisTemplate redis;
    @Mock EntitlementService entitlements;

    @Test
    void freePlanUserDailyLimitIsEnforced() {
        when(entitlements.plan("org")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));
        when(redis.execute(any(RedisScript.class), anyList(), eq("30"), eq("5"), eq("172800")))
                .thenReturn(0L);

        ApiException error = assertThrows(ApiException.class,
                () -> new AiQuotaService(redis, entitlements).consume("org", "user"));

        assertEquals("AI_QUOTA_EXCEEDED", error.code());
        verify(redis).execute(any(RedisScript.class), anyList(), eq("30"), eq("5"), eq("172800"));
    }

    @Test
    void availableQuotaConsumesBothCountersAtomically() {
        when(entitlements.plan("org")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));
        when(redis.execute(any(RedisScript.class), anyList(), eq("30"), eq("5"), eq("172800")))
                .thenReturn(1L);

        new AiQuotaService(redis, entitlements).consume("org", "user");

        verify(redis).execute(any(RedisScript.class), anyList(), eq("30"), eq("5"), eq("172800"));
    }

    @Test
    void unknownRedisResultFailsClosed() {
        when(entitlements.plan("org")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));

        ApiException error = assertThrows(ApiException.class,
                () -> new AiQuotaService(redis, entitlements).consume("org", "user"));

        assertEquals("AI_QUOTA_UNAVAILABLE", error.code());
    }
}
