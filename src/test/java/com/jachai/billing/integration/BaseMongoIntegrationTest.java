package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("integration")
public abstract class BaseMongoIntegrationTest {

    protected static MongoClient mongoClient;
    protected static MongoDatabase testDatabase;
    protected static String databaseName;
    protected Set<String> createdDocumentIds = new HashSet<>();

    @BeforeAll
    static void validateTestUriAndConnect() {
        String testUri = System.getenv("MONGODB_TEST_URI");
        if (testUri == null || testUri.isBlank()) {
            assumeTrue(false, "MONGODB_TEST_URI not set - integration tests skipped");
        }

        try {
            ConnectionString connectionString = new ConnectionString(testUri);
            databaseName = connectionString.getDatabase();
            if (databaseName == null || !databaseName.startsWith("jachai_it_")) {
                fail("MONGODB_TEST_URI must point to a dedicated database beginning with 'jachai_it_'");
            }
        } catch (Exception e) {
            fail("MONGODB_TEST_URI is invalid");
        }

        mongoClient = MongoClients.create(testUri);
        testDatabase = mongoClient.getDatabase(databaseName);
    }

    @AfterAll
    static void closeClient() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    @AfterEach
    void cleanupTestDocuments() {
        if (testDatabase != null && !createdDocumentIds.isEmpty()) {
            Set<String> idsToClean = new HashSet<>(createdDocumentIds);
            createdDocumentIds.clear();
            for (String id : idsToClean) {
                try {
                    String[] parts = id.split(":", 2);
                    if (parts.length == 2) {
                        testDatabase.getCollection(parts[0]).deleteOne(new Document("_id", parts[1]));
                    }
                } catch (Exception e) {
                    fail("Failed to clean up test document: " + e.getMessage());
                }
            }
        }
    }

    protected void trackDocumentId(String collectionName, String documentId) {
        createdDocumentIds.add(collectionName + ":" + documentId);
    }

    protected void trackDocumentIds(String collectionName, Set<String> documentIds) {
        for (String id : documentIds) {
            trackDocumentId(collectionName, id);
        }
    }

    protected String generateTestId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString();
    }
}
