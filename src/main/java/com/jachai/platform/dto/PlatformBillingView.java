package com.jachai.platform.dto;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.SubscriptionStatus;
import java.time.Instant;

public record PlatformBillingView(String organizationId, boolean subscriptionPresent,
                                  PlanTier tier, SubscriptionStatus status,
                                  Instant currentPeriodEnd, String lastPaidOrderId,
                                  Instant updatedAt) { }
