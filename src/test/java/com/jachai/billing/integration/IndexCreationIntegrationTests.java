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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

class IndexCreationIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;

    private void setupStore() {
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoClient, databaseName);
        mongoTemplate = new MongoTemplate(factory);
    }

    @Test
    void uniqueIndexOnIdPreventsDuplicateInserts() {
        setupStore();

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

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

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
        setupStore();

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

        assertTrue(hasTtlIndex, "Application should create TTL index on expiresAt field in UsageBucket mapping");
    }

    @Test
    void applicationCreatedTtlIndexConfigurationIsVerified() {
        setupStore();

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
        setupStore();

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
}
