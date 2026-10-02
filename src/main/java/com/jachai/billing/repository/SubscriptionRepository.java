package com.jachai.billing.repository;

import com.jachai.billing.model.Subscription;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SubscriptionRepository extends MongoRepository<Subscription, String> { }
