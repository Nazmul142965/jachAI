package com.jachai.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTests {
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock EntitlementService entitlements;

    @Test
    void freePlanUserDailyLimitIsEnforced() {
        when(entitlements.plan("org")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L, 6L);

        ApiException error = assertThrows(ApiException.class,
                () -> new AiQuotaService(redis, entitlements).consume("org", "user"));

        assertEquals("AI_QUOTA_EXCEEDED", error.code());
    }
}
