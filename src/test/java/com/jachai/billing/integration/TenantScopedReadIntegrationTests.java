package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.repository.MongoEntitlementUsageStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

class TenantScopedReadIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;
    private MongoEntitlementUsageStore store;

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

        String orgA = "org-a-it-" + System.currentTimeMillis();
        String orgB = "org-b-it-" + System.currentTimeMillis();

        UsageBucket bucketA = new UsageBucket(
                orgA + ":INVITATION:2026-10",
                orgA,
                UsageMetric.INVITATION,
                "2026-10",
                5,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        UsageBucket bucketB = new UsageBucket(
                orgB + ":INVITATION:2026-10",
                orgB,
                UsageMetric.INVITATION,
                "2026-10",
                10,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(bucketA);
        mongoTemplate.insert(bucketB);

        Map<String, UsageBucket> foundForA = store.buckets(orgA,
                List.of(orgA + ":INVITATION:2026-10", orgB + ":INVITATION:2026-10"));

        assertEquals(1, foundForA.size());
        assertTrue(foundForA.containsKey(orgA + ":INVITATION:2026-10"));
        assertEquals(5, foundForA.get(orgA + ":INVITATION:2026-10").used());

        Map<String, UsageBucket> foundForB = store.buckets(orgB,
                List.of(orgA + ":INVITATION:2026-10", orgB + ":INVITATION:2026-10"));

        assertEquals(1, foundForB.size());
        assertTrue(foundForB.containsKey(orgB + ":INVITATION:2026-10"));
        assertEquals(10, foundForB.get(orgB + ":INVITATION:2026-10").used());
    }

    @Test
    void mongoTemplateQueryEnforcesTenantIsolation() {
        setupStore();

        String orgA = "org-a-it-" + System.currentTimeMillis();
        String orgB = "org-b-it-" + System.currentTimeMillis();

        UsageBucket bucketA = new UsageBucket(
                orgA + ":CERTIFICATE:2026-10",
                orgA,
                UsageMetric.CERTIFICATE,
                "2026-10",
                3,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        UsageBucket bucketB = new UsageBucket(
                orgB + ":CERTIFICATE:2026-10",
                orgB,
                UsageMetric.CERTIFICATE,
                "2026-10",
                7,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

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

        String orgA = "org-a-it-" + System.currentTimeMillis();
        String orgB = "org-b-it-" + System.currentTimeMillis();

        mongoTemplate.getCollection("assignments").insertMany(List.of(
                new Document("_id", "assign-1")
                        .append("organizationId", orgA)
                        .append("createdAt", java.util.Date.from(Instant.parse("2026-10-01T00:00:00Z")))
                        .append("campaignId", null),
                new Document("_id", "assign-2")
                        .append("organizationId", orgA)
                        .append("createdAt", java.util.Date.from(Instant.parse("2026-10-02T00:00:00Z")))
                        .append("campaignId", null),
                new Document("_id", "assign-3")
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

        mongoTemplate.getCollection("assignments").deleteMany(new Document());
    }
}
