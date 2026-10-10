package com.jachai.billing.repository;

import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.model.PlanTier;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SubscriptionRepository extends MongoRepository<Subscription, String> {
    long countByTierAndStatusAndCurrentPeriodEndAfter(PlanTier tier, SubscriptionStatus status, Instant now);
}
