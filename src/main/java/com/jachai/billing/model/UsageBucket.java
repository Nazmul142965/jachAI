package com.jachai.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("entitlement_usage")
public record UsageBucket(@Id String id, String organizationId, UsageMetric metric,
                          String window, long used, Instant createdAt,
                          @Indexed(expireAfter = "0s") Instant expiresAt) { }
