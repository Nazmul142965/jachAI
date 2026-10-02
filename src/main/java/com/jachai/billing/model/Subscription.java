package com.jachai.billing.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("subscriptions")
public record Subscription(@Id String organizationId, PlanTier tier, SubscriptionStatus status,
                           String lastPaidOrderId,
                           Instant currentPeriodEnd, Instant updatedAt,
                           List<String> appliedOrderIds, @Version Long revision) {

    public Subscription(String organizationId, PlanTier tier, SubscriptionStatus status,
                        String lastPaidOrderId, Instant currentPeriodEnd, Instant updatedAt, Long revision) {
        this(organizationId, tier, status, lastPaidOrderId, currentPeriodEnd,
                updatedAt, List.of(), revision);
    }
}
