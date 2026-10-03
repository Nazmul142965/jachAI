package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("integration")
public abstract class BaseMongoIntegrationTest {

    protected static MongoClient mongoClient;
    protected static MongoDatabase testDatabase;
    protected static String databaseName;

    /**
     * Concurrent reservations and reconciliations register IDs from worker threads, so the tracker is
     * a concurrent set. Subclasses must register an ID before performing the matching write.
     */
    protected final Set<String> createdDocumentIds = ConcurrentHashMap.newKeySet();

    /**
     * Rejects a non-{@code jachai_it_} URI while Spring is still customizing the environment. This hook
     * runs before the context is refreshed, which is when the {@code MongoClient} is created and
     * automatic index creation happens, so a rejected URI is never used for a connection.
     */
    @DynamicPropertySource
    protected static void bindDedicatedTestDatabase(DynamicPropertyRegistry registry) {
        String testUri = System.getenv("MONGODB_TEST_URI");
        MongoTestUriGuard.requireDedicatedTestDatabase(testUri);
        registry.add("spring.data.mongodb.uri", () -> testUri);
    }

    /**
     * For the classes that build their own {@code MongoTemplate}, validates the URI before any client
     * is created, and skips the whole container when {@code MONGODB_TEST_URI} is not set.
     */
    @BeforeAll
    protected static void validateTestUriAndConnect() {
        String testUri = System.getenv("MONGODB_TEST_URI");
        if (testUri == null || testUri.isBlank()) {
            assumeTrue(false, "MONGODB_TEST_URI not set - live MongoDB integration tests skipped");
        }
        databaseName = MongoTestUriGuard.requireDedicatedTestDatabase(testUri);
        mongoClient = MongoClients.create(testUri);
        testDatabase = mongoClient.getDatabase(databaseName);
    }

    @AfterAll
    protected static void closeClient() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    /**
     * Deletes every tracked document by ID. A single failing delete must not abandon the remaining
     * IDs, so failures are collected, every delete is still attempted, and the result is reported once
     * afterwards. Reports contain collection names, document IDs and exception types, never the URI or
     * credentials.
     */
    @AfterEach
    protected void cleanupTestDocuments() {
        if (testDatabase == null || createdDocumentIds.isEmpty()) {
            return;
        }
        Set<String> idsToClean = Set.copyOf(createdDocumentIds);
        List<String> failures = new ArrayList<>();
        for (String trackedId : idsToClean) {
            String[] parts = trackedId.split(":", 2);
            if (parts.length != 2) {
                failures.add("malformed tracker entry for collection '" + parts[0] + "'");
                continue;
            }
            try {
                testDatabase.getCollection(parts[0]).deleteOne(new Document("_id", parts[1]));
            } catch (RuntimeException failure) {
                failures.add(parts[0] + " -> " + failure.getClass().getSimpleName());
            }
        }
        createdDocumentIds.removeAll(idsToClean);
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + idsToClean.size()
                    + " tracked test document(s) could not be deleted: " + failures);
        }
    }

    protected void trackDocumentId(String collectionName, String documentId) {
        createdDocumentIds.add(collectionName + ":" + documentId);
    }

    protected void trackDocumentIds(String collectionName, Set<String> documentIds) {
        for (String documentId : documentIds) {
            trackDocumentId(collectionName, documentId);
        }
    }

    protected String generateTestId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
