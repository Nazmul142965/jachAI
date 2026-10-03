package com.jachai.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("entitlement_usage")
public record UsageBucket(@Id String id, String organizationId, UsageMetric metric,
                          String window, long used, Instant createdAt,
                          @Indexed(expireAfter = "0s") Instant expiresAt,
                          Instant lastChangedAt, Instant lastReconciledAt,
                          String reconciledByUserId, Long revision) {

    public UsageBucket(String id, String organizationId, UsageMetric metric, String window,
                       long used, Instant createdAt, Instant expiresAt, Instant lastChangedAt) {
        this(id, organizationId, metric, window, used, createdAt, expiresAt, lastChangedAt, null, null, 1L);
    }

    public UsageBucket(String id, String organizationId, UsageMetric metric, String window,
                       long used, Instant createdAt, Instant expiresAt, Instant lastChangedAt,
                       Instant lastReconciledAt, String reconciledByUserId) {
        this(id, organizationId, metric, window, used, createdAt, expiresAt, lastChangedAt,
                lastReconciledAt, reconciledByUserId, 1L);
    }
}
