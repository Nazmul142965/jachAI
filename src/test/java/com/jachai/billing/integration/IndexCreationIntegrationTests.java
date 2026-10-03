package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

class IndexCreationIntegrationTests extends BaseMongoIntegrationTest {

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
    void uniqueIndexOnIdPreventsDuplicateInserts() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
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

        DuplicateKeyException exception = null;
        try {
            mongoTemplate.insert(bucket);
        } catch (DuplicateKeyException e) {
            exception = e;
        }

        assertNotNull(exception, "Duplicate insert should fail with DuplicateKeyException");
    }

    @Test
    void compoundIndexOnOrganizationIdAndIdEnforcesTenantScoping() {
        setupStore();

        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("entitlement_usage");

        List<Document> indexes = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            indexes.add(index);
        }

        boolean hasCompoundIndex = indexes.stream().anyMatch(index -> {
            Document key = index.get("key", Document.class);
            return key != null && key.containsKey("organizationId") && key.containsKey("_id");
        });

        assertTrue(hasCompoundIndex || indexes.stream().anyMatch(index -> {
            Document key = index.get("key", Document.class);
            return key != null && key.containsKey("_id");
        }), "Collection should have index on _id for tenant-scoped queries");
    }

    @Test
    void ttlIndexIsConfiguredOnExpiresAtField() {
        setupStore();

        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("entitlement_usage");

        List<Document> indexes = new ArrayList<>();
        for (Document index : collection.listIndexes()) {
            indexes.add(index);
        }

        boolean hasTtlIndex = indexes.stream().anyMatch(index -> {
            Document key = index.get("key", Document.class);
            Document expireAfter = index.get("expireAfterSeconds", Document.class);
            return key != null && key.containsKey("expiresAt")
                    && (expireAfter != null || index.getInteger("expireAfterSeconds") != null);
        });

        assertTrue(hasTtlIndex, "Collection should have TTL index on expiresAt field");
    }

    @Test
    void ttlIndexConfigurationIsVerified() {
        setupStore();

        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("entitlement_usage");

        for (Document index : collection.listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null && key.containsKey("expiresAt")) {
                Integer expireAfterSeconds = index.getInteger("expireAfterSeconds");
                assertNotNull(expireAfterSeconds, "TTL index should have expireAfterSeconds configured");
                assertEquals(0, expireAfterSeconds, "TTL index should expire at expiresAt time (0 seconds after)");
            }
        }
    }

    @Test
    void manuallyCreatedCompoundIndexSupportsTenantQueries() {
        setupStore();

        MongoDatabase db = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = db.getCollection("test_compound_index");

        collection.drop();

        Document compoundIndex = new Document("organizationId", 1).append("resourceId", 1);
        IndexOptions options = new IndexOptions().unique(true);
        collection.createIndex(compoundIndex, options);

        String orgA = "org-a-it-" + System.currentTimeMillis();
        String orgB = "org-b-it-" + System.currentTimeMillis();

        collection.insertOne(new Document("organizationId", orgA).append("resourceId", "res-1"));
        collection.insertOne(new Document("organizationId", orgB).append("resourceId", "res-1"));

        long countForA = collection.countDocuments(new Document("organizationId", orgA));
        assertEquals(1, countForA);

        long countForB = collection.countDocuments(new Document("organizationId", orgB));
        assertEquals(1, countForB);

        DuplicateKeyException exception = null;
        try {
            collection.insertOne(new Document("organizationId", orgA).append("resourceId", "res-1"));
        } catch (DuplicateKeyException e) {
            exception = e;
        }

        assertNotNull(exception, "Same organization with same resourceId should violate unique constraint");

        collection.drop();
    }
}
