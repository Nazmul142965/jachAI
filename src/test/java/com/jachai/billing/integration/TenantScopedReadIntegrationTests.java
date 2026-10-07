package com.jachai.billing.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.repository.MongoEntitlementUsageStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class TenantScopedReadIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;
    private MongoEntitlementUsageStore store;

    private void setupStore() {
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(mongoClient, databaseName);
        mongoTemplate = new MongoTemplate(factory);
        ObjectProvider<MongoTemplate> provider = new ObjectProvider<MongoTemplate>() {
            @Override
            public MongoTemplate getObject(Object... args) {
                return mongoTemplate;
            }

            @Override
            public MongoTemplate getIfAvailable() {
                return mongoTemplate;
            }

            @Override
            public MongoTemplate getIfUnique() {
                return mongoTemplate;
            }
        };
        store = new MongoEntitlementUsageStore(provider);
    }

    @Test
    void usageBucketReadIsScopedToTenant() {
        setupStore();

        String orgA = generateTestId("org-a");
        String orgB = generateTestId("org-b");

        String bucketIdA = orgA + ":INVITATION:2026-10";
        String bucketIdB = orgB + ":INVITATION:2026-10";

        UsageBucket bucketA = new UsageBucket(
                bucketIdA,
                orgA,
                UsageMetric.INVITATION,
                "2026-10",
                5,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        UsageBucket bucketB = new UsageBucket(
                bucketIdB,
                orgB,
                UsageMetric.INVITATION,
                "2026-10",
                10,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        trackDocumentId("entitlement_usage", bucketIdA);
        trackDocumentId("entitlement_usage", bucketIdB);
        mongoTemplate.insert(bucketA);
        mongoTemplate.insert(bucketB);

        Map<String, UsageBucket> foundForA = store.buckets(orgA,
                List.of(bucketIdA, bucketIdB));

        assertEquals(1, foundForA.size());
        assertTrue(foundForA.containsKey(bucketIdA));
        assertEquals(5, foundForA.get(bucketIdA).used());

        Map<String, UsageBucket> foundForB = store.buckets(orgB,
                List.of(bucketIdA, bucketIdB));

        assertEquals(1, foundForB.size());
        assertTrue(foundForB.containsKey(bucketIdB));
        assertEquals(10, foundForB.get(bucketIdB).used());
    }

    @Test
    void mongoTemplateQueryEnforcesTenantIsolation() {
        setupStore();

        String orgA = generateTestId("org-a");
        String orgB = generateTestId("org-b");

        String bucketIdA = orgA + ":CERTIFICATE:2026-10";
        String bucketIdB = orgB + ":CERTIFICATE:2026-10";

        UsageBucket bucketA = new UsageBucket(
                bucketIdA,
                orgA,
                UsageMetric.CERTIFICATE,
                "2026-10",
                3,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        UsageBucket bucketB = new UsageBucket(
                bucketIdB,
                orgB,
                UsageMetric.CERTIFICATE,
                "2026-10",
                7,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        trackDocumentId("entitlement_usage", bucketIdA);
        trackDocumentId("entitlement_usage", bucketIdB);
        mongoTemplate.insert(bucketA);
        mongoTemplate.insert(bucketB);

        Query queryForA = Query.query(Criteria.where("organizationId").is(orgA));
        List<UsageBucket> resultsForA = mongoTemplate.find(queryForA, UsageBucket.class);

        assertEquals(1, resultsForA.size());
        assertEquals(orgA, resultsForA.get(0).organizationId());
        assertEquals(3, resultsForA.get(0).used());

        Query queryForB = Query.query(Criteria.where("organizationId").is(orgB));
        List<UsageBucket> resultsForB = mongoTemplate.find(queryForB, UsageBucket.class);

        assertEquals(1, resultsForB.size());
        assertEquals(orgB, resultsForB.get(0).organizationId());
        assertEquals(7, resultsForB.get(0).used());

        Query queryAll = Query.query(Criteria.where("metric").is(UsageMetric.CERTIFICATE));
        List<UsageBucket> allResults = mongoTemplate.find(queryAll, UsageBucket.class);

        assertEquals(2, allResults.size());
    }

    @Test
    void aggregateQueriesAreTenantScoped() {
        setupStore();

        String orgA = generateTestId("org-a");
        String orgB = generateTestId("org-b");

        String assignId1 = generateTestId("assign");
        String assignId2 = generateTestId("assign");
        String assignId3 = generateTestId("assign");

        trackDocumentId("assignments", assignId1);
        trackDocumentId("assignments", assignId2);
        trackDocumentId("assignments", assignId3);
        mongoTemplate.getCollection("assignments").insertMany(List.of(
                new Document("_id", assignId1)
                        .append("organizationId", orgA)
                        .append("createdAt", java.util.Date.from(Instant.parse("2026-10-01T00:00:00Z")))
                        .append("campaignId", null),
                new Document("_id", assignId2)
                        .append("organizationId", orgA)
                        .append("createdAt", java.util.Date.from(Instant.parse("2026-10-02T00:00:00Z")))
                        .append("campaignId", null),
                new Document("_id", assignId3)
                        .append("organizationId", orgB)
                        .append("createdAt", java.util.Date.from(Instant.parse("2026-10-01T00:00:00Z")))
                        .append("campaignId", null)
        ));

        long totalForA = store.total("assignments", orgA);
        assertEquals(2, totalForA);

        long totalForB = store.total("assignments", orgB);
        assertEquals(1, totalForB);

        Map<String, Long> monthlyForA = store.byUtcMonth("assignments", orgA, "createdAt",
                Instant.parse("2026-08-01T00:00:00Z"), new Document("campaignId", null));
        assertEquals(1, monthlyForA.size());
        assertEquals(2L, monthlyForA.get("2026-10"));

        Map<String, Long> monthlyForB = store.byUtcMonth("assignments", orgB, "createdAt",
                Instant.parse("2026-08-01T00:00:00Z"), new Document("campaignId", null));
        assertEquals(1, monthlyForB.size());
        assertEquals(1L, monthlyForB.get("2026-10"));
    }
}
