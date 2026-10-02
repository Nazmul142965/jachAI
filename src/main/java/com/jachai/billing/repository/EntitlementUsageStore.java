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

    boolean replaceUsage(String organizationId, String bucketId, long expectedUsage, long newUsage,
                         Instant reconciledAt, String actorUserId);

    long total(String collection, String organizationId);

    long distinct(String collection, String organizationId, String field);

    Map<String, Long> byUtcMonth(String collection, String organizationId, String dateField,
                                 Instant fromInclusive, Document extraMatch);

    long undated(String collection, String organizationId, String dateField);
}
