package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("integration")
public abstract class BaseMongoIntegrationTest {

    protected static MongoClient mongoClient;
    protected static MongoDatabase testDatabase;
    protected static String databaseName;
    protected static final List<String> createdCollections = new ArrayList<>();

    @BeforeAll
    static void validateTestUriAndConnect() {
        String testUri = System.getenv("MONGODB_TEST_URI");
        assumeTrue(testUri != null && !testUri.isBlank(),
                "MONGODB_TEST_URI must be set for integration tests");

        databaseName = extractDatabaseName(testUri);
        assumeTrue(databaseName != null && databaseName.startsWith("jachai_it_"),
                "MONGODB_TEST_URI must point to a dedicated database beginning with 'jachai_it_'");

        mongoClient = MongoClients.create(testUri);
        testDatabase = mongoClient.getDatabase(databaseName);
    }

    @AfterAll
    static void cleanup() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    protected static String extractDatabaseName(String uri) {
        try {
            String[] parts = uri.split("/");
            if (parts.length > 1) {
                String dbPart = parts[parts.length - 1];
                int queryIndex = dbPart.indexOf('?');
                if (queryIndex > 0) {
                    return dbPart.substring(0, queryIndex);
                }
                return dbPart;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    protected void cleanupCollection(String collectionName) {
        if (testDatabase != null) {
            testDatabase.getCollection(collectionName).deleteMany(new org.bson.Document());
        }
    }

    protected void ensureCollectionExists(String collectionName) {
        if (testDatabase != null && !createdCollections.contains(collectionName)) {
            testDatabase.createCollection(collectionName);
            createdCollections.add(collectionName);
        }
    }
}
