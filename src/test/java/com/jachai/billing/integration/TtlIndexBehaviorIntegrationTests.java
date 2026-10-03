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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

class TtlIndexBehaviorIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;

    @Override
    @AfterEach
    void cleanup() {
        if (mongoTemplate != null) {
            mongoTemplate.getCollection("entitlement_usage").deleteMany(new Document());
        }
    }

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

        assertTrue(hasTtlIndex, "entitlement_usage collection should have TTL index on expiresAt");
    }

    @Test
    void ttlIndexConfigurationExpiresAtZeroSeconds() {
        setupStore();

        MongoCollection<Document> collection = testDatabase.getCollection("entitlement_usage");

        for (Document index : collection.listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null && key.containsKey("expiresAt")) {
                Integer expireAfterSeconds = index.getInteger("expireAfterSeconds");
                assertNotNull(expireAfterSeconds, "TTL index should have expireAfterSeconds configured");
                assertEquals(0, expireAfterSeconds,
                        "TTL index should expire at the exact expiresAt timestamp (0 seconds after)");
            }
        }
    }

    @Test
    void documentWithPastExpiresAtIsEligibleForDeletion() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
        String bucketId = orgId + ":INVITATION:2026-10";

        Instant pastExpiry = Instant.now().minusSeconds(3600);

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now().minusSeconds(7200),
                pastExpiry,
                Instant.now().minusSeconds(7200)
        );

        mongoTemplate.insert(bucket);

        UsageBucket found = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(found, "Document should be inserted before TTL cleanup runs");

        Document doc = mongoTemplate.getCollection("entitlement_usage")
                .find(new Document("_id", bucketId)).first();
        assertNotNull(doc, "Document exists in collection (TTL deletion is not immediate)");
    }

    @Test
    void documentWithFutureExpiresAtPersists() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
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

        UsageBucket found = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(found, "Document with future expiry should persist");
        assertEquals(futureExpiry, found.expiresAt());
    }

    @Test
    void documentWithNullExpiresAtIsNotSubjectToTtl() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
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

        UsageBucket found = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(found, "Document with null expiresAt should persist");
    }

    @Test
    void manuallyCreatedTtlIndexOnTestCollection() {
        setupStore();

        MongoCollection<Document> testCollection = testDatabase.getCollection("test_ttl_collection");
        testCollection.drop();

        Document ttlIndex = new Document("deleteAt", 1);
        com.mongodb.client.model.IndexOptions options = new com.mongodb.client.model.IndexOptions()
                .expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS);
        testCollection.createIndex(ttlIndex, options);

        List<Document> indexes = new ArrayList<>();
        for (Document index : testCollection.listIndexes()) {
            indexes.add(index);
        }

        boolean hasTtlIndex = indexes.stream().anyMatch(index -> {
            Document key = index.get("key", Document.class);
            return key != null && key.containsKey("deleteAt");
        });

        assertTrue(hasTtlIndex, "Test collection should have TTL index on deleteAt");

        testCollection.drop();
    }

    @Test
    void ttlIndexDoesNotDeleteImmediately() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
        String bucketId = orgId + ":INVITATION:2026-10";

        Instant justExpired = Instant.now().minusSeconds(1);

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now().minusSeconds(10),
                justExpired,
                Instant.now().minusSeconds(10)
        );

        mongoTemplate.insert(bucket);

        UsageBucket foundImmediately = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(foundImmediately,
                "Document should still exist immediately after expiry (TTL cleanup runs periodically)");
    }

    @Test
    void multipleDocumentsWithDifferentExpiryTimes() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();

        Instant pastExpiry = Instant.now().minusSeconds(3600);
        Instant futureExpiry = Instant.now().plusSeconds(3600);

        UsageBucket pastBucket = new UsageBucket(
                orgId + ":INVITATION:past",
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now().minusSeconds(7200),
                pastExpiry,
                Instant.now().minusSeconds(7200)
        );

        UsageBucket futureBucket = new UsageBucket(
                orgId + ":INVITATION:future",
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                2,
                Instant.now(),
                futureExpiry,
                Instant.now()
        );

        mongoTemplate.insert(pastBucket);
        mongoTemplate.insert(futureBucket);

        long count = mongoTemplate.getCollection("entitlement_usage").countDocuments(
                new Document("organizationId", orgId));

        assertEquals(2, count, "Both documents should exist before TTL cleanup runs");
    }
}
