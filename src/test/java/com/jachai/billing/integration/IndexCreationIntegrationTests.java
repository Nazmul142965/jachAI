package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class IndexCreationIntegrationTests extends BaseMongoIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void uniqueIndexOnIdPreventsDuplicateInserts() {
        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        trackDocumentId("entitlement_usage", bucketId);
        mongoTemplate.insert(bucket);

        DuplicateKeyException exception = null;
        try {
            mongoTemplate.insert(bucket);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            exception = e;
        }

        assertNotNull(exception, "Duplicate insert should fail with DuplicateKeyException");
    }

    @Test
    void applicationCreatedTtlIndexIsConfiguredOnExpiresAtField() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("entitlement_usage");

        List<Document> indexes = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            indexes.add(index);
        }

        boolean hasTtlIndex = indexes.stream().anyMatch(index -> {
            Document key = index.get("key", Document.class);
            return key != null && key.containsKey("expiresAt");
        });

        assertTrue(hasTtlIndex, "Spring Boot should create TTL index on expiresAt field in UsageBucket mapping");
    }

    @Test
    void applicationCreatedTtlIndexConfigurationIsVerified() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("entitlement_usage");

        boolean foundTtlIndex = false;
        for (Document index : collection.listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null && key.containsKey("expiresAt")) {
                foundTtlIndex = true;
                Integer expireAfterSeconds = index.getInteger("expireAfterSeconds");
                assertNotNull(expireAfterSeconds, "TTL index should have expireAfterSeconds configured");
                assertEquals(0, expireAfterSeconds, "TTL index should expire at expiresAt time (0 seconds after)");
            }
        }

        assertTrue(foundTtlIndex, "TTL index on expiresAt must exist for this test to pass");
    }

    @Test
    void applicationCreatedIdIndexExists() {
        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("entitlement_usage");

        boolean hasIdIndex = false;
        for (Document index : collection.listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null && key.containsKey("_id") && key.size() == 1) {
                hasIdIndex = true;
                break;
            }
        }

        assertTrue(hasIdIndex, "MongoDB should have default _id index");
    }

    @Test
    void operationalBacklogQueriesHaveApplicationCreatedIndexes() {
        assertIndex("email_jobs", "mail_due", "status", "nextAttemptAt", "leaseUntil");
        assertIndex("payment_orders", "payment_status_created", "status", "createdAt");
        assertIndex("payment_orders", "payment_status_verified", "status", "verifiedAt");
        assertIndex("platform_usage_repair_events", "stage", "stage");
        assertUniqueIndex("platform_plan_events", "platform_plan_org_revision_unique",
                "organizationId", "revision");
    }

    @Test
    void tutorActiveAttemptLookupHasApplicationCreatedIndex() {
        assertIndex("attempts", "attempt_org_user_active_deadline",
                "organizationId", "userId", "status", "deadline");
    }

    private void assertUniqueIndex(String collectionName, String name, String... fields) {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName).getCollection(collectionName);
        for (Document index : collection.listIndexes()) {
            if (!name.equals(index.getString("name"))) continue;
            assertTrue(Boolean.TRUE.equals(index.getBoolean("unique")), name + " must be unique");
            Document keys = index.get("key", Document.class);
            assertNotNull(keys, name + " must have index keys");
            assertEquals(fields.length, keys.size());
            for (String field : fields) assertTrue(keys.containsKey(field), name + " is missing " + field);
            return;
        }
        throw new AssertionError("Expected application-created index " + name + " on " + collectionName);
    }

    private void assertIndex(String collectionName, String name, String... fields) {
        MongoCollection<Document> collection = mongoClient.getDatabase(databaseName).getCollection(collectionName);
        boolean found = false;
        for (Document index : collection.listIndexes()) {
            if (!name.equals(index.getString("name"))) continue;
            Document keys = index.get("key", Document.class);
            assertNotNull(keys, name + " must have index keys");
            assertEquals(fields.length, keys.size(), name + " should not have extra fields");
            for (String field : fields) assertTrue(keys.containsKey(field), name + " is missing " + field);
            found = true;
        }
        assertTrue(found, "Expected application-created index " + name + " on " + collectionName);
    }
}
