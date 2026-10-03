package com.jachai.billing.repository;

import com.jachai.billing.model.UsageBucket;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.bson.Document;

public interface EntitlementUsageStore {
    String ASSESSMENT_VERSIONS = "assessment_versions";
    String ASSIGNMENTS = "assignments";
    String EMAIL_INVITATIONS = "campaign_email_invitations";
    String CERTIFICATES = "certificates";

    Map<String, UsageBucket> buckets(String organizationId, Collection<String> bucketIds);

    boolean insertBucket(UsageBucket bucket);

    /**
     * Replaces {@code used} only while the observed monotonic revision still matches. A
     * reserve+refund pair can restore {@code used} and its BSON-millisecond activity timestamp,
     * but must not restore the revision. Null matches a legacy bucket without this field.
     *
     * @return true when exactly one bucket matched and was updated
     */
    boolean replaceUsage(String organizationId, String bucketId, long expectedUsage, long newUsage,
                         Long expectedRevision, Instant reconciledAt, String actorUserId);

    long total(String collection, String organizationId);

    long distinct(String collection, String organizationId, String field);

    Map<String, Long> byUtcMonth(String collection, String organizationId, String dateField,
                                 Instant fromInclusive, Document extraMatch);

    long undated(String collection, String organizationId, String dateField);
}
