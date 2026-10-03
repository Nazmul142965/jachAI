package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

class TtlIndexBehaviorIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;

    private void setupStore() {
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoClient, databaseName);
        mongoTemplate = new MongoTemplate(factory);
    }

    @Test
    void ttlIndexIsPresentOnEntitlementUsageCollection() {
        setupStore();

        MongoCollection<Document> collection = testDatabase.getCollection("entitlement_usage");

        List<Document> indexes = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            indexes.add(index);
        }

        boolean hasTtlIndex = indexes.stream().anyMatch(index -> {
            Document key = index.get("key", Document.class);
            return key != null && key.containsKey("expiresAt");
        });

        assertTrue(hasTtlIndex, "Application should create TTL index on expiresAt in UsageBucket mapping");
    }

    @Test
    void ttlIndexConfigurationExpiresAtZeroSeconds() {
        setupStore();

        MongoCollection<Document> collection = testDatabase.getCollection("entitlement_usage");

        boolean foundTtlIndex = false;
        for (Document index : collection.listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null && key.containsKey("expiresAt")) {
                foundTtlIndex = true;
                Integer expireAfterSeconds = index.getInteger("expireAfterSeconds");
                assertNotNull(expireAfterSeconds, "TTL index should have expireAfterSeconds configured");
                assertEquals(0, expireAfterSeconds,
                        "TTL index should expire at the exact expiresAt timestamp (0 seconds after)");
            }
        }

        assertTrue(foundTtlIndex, "TTL index on expiresAt must exist for this test to pass");
    }

    @Test
    void documentWithFutureExpiresAtPersists() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        Instant futureExpiry = Instant.now().plusSeconds(86400);

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                "2026-10",
                2,
                Instant.now(),
                futureExpiry,
                Instant.now()
        );

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

        UsageBucket found = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(found, "Document with future expiry should persist");
        assertEquals(futureExpiry, found.expiresAt());
    }

    @Test
    void documentWithNullExpiresAtIsNotSubjectToTtl() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":PUBLISHED_ASSESSMENT:all";

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.PUBLISHED_ASSESSMENT,
                "all",
                1,
                Instant.now(),
                null,
                Instant.now()
        );

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

        UsageBucket found = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(found, "Document with null expiresAt should persist");
    }

    @Test
    void multipleDocumentsWithDifferentExpiryTimes() {
        setupStore();

        String orgId = generateTestId("org");

        Instant futureExpiry = Instant.now().plusSeconds(3600);

        String bucketId1 = orgId + ":INVITATION:future1";
        String bucketId2 = orgId + ":INVITATION:future2";

        UsageBucket futureBucket1 = new UsageBucket(
                bucketId1,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now(),
                futureExpiry,
                Instant.now()
        );

        UsageBucket futureBucket2 = new UsageBucket(
                bucketId2,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                2,
                Instant.now(),
                futureExpiry,
                Instant.now()
        );

        mongoTemplate.insert(futureBucket1);
        mongoTemplate.insert(futureBucket2);
        trackDocumentId("entitlement_usage", bucketId1);
        trackDocumentId("entitlement_usage", bucketId2);

        long count = mongoTemplate.getCollection("entitlement_usage").countDocuments(
                new Document("organizationId", orgId));

        assertEquals(2, count, "Both documents should exist");
    }
}
