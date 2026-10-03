package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.repository.MongoEntitlementUsageStore;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.billing.service.EntitlementService;
import com.jachai.organization.service.TenantAccessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = {"PLATFORM_ADMIN"})
class EntitlementReservationCasIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;
    private MongoEntitlementUsageStore store;
    private EntitlementService entitlementService;

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
        SubscriptionRepository subscriptionRepository = new SubscriptionRepository(mongoTemplate);
        TenantAccessService tenantAccessService = new TenantAccessService(subscriptionRepository, null);
        entitlementService = new EntitlementService(subscriptionRepository, mongoTemplate, tenantAccessService);
    }

    @Test
    void replaceUsageWithRevisionCompareAndSwap() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                "2026-10",
                5,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

        boolean replaced = store.replaceUsage(orgId, bucketId, 5, 3, 1L,
                Instant.now(), "admin-it");

        assertTrue(replaced, "CAS should succeed when revision matches");

        UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(updated);
        assertEquals(3, updated.used());
        assertEquals(2L, updated.revision());
    }

    @Test
    void replaceUsageFailsWhenRevisionDoesNotMatch() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                2,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

        boolean replaced = store.replaceUsage(orgId, bucketId, 2, 1, 999L,
                Instant.now(), "admin-it");

        assertFalse(replaced, "CAS should fail when revision does not match");

        UsageBucket unchanged = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(unchanged);
        assertEquals(2, unchanged.used());
        assertEquals(1L, unchanged.revision());
    }

    @Test
    void replaceUsageFailsWhenUsageDoesNotMatch() {
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

        boolean replaced = store.replaceUsage(orgId, bucketId, 99, 2, 1L,
                Instant.now(), "admin-it");

        assertFalse(replaced, "CAS should fail when usage does not match");

        UsageBucket unchanged = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(unchanged);
        assertEquals(1, unchanged.used());
    }

    @Test
    void replaceUsageFailsWhenOrganizationIdDoesNotMatch() {
        setupStore();

        String orgId = generateTestId("org");
        String wrongOrgId = generateTestId("wrong-org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                "2026-10",
                4,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

        boolean replaced = store.replaceUsage(wrongOrgId, bucketId, 4, 3, 1L,
                Instant.now(), "admin-it");

        assertFalse(replaced, "CAS should fail when organizationId does not match");

        UsageBucket unchanged = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(unchanged);
        assertEquals(4, unchanged.used());
    }

    @Test
    void legacyBucketWithoutRevisionInitializesRevisionOnFirstReplace() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        Document legacyBucket = new Document("_id", bucketId)
                .append("organizationId", orgId)
                .append("metric", "INVITATION")
                .append("window", "2026-10")
                .append("used", 3)
                .append("createdAt", Instant.now())
                .append("expiresAt", Instant.now().plusSeconds(86400))
                .append("lastChangedAt", Instant.now());

        mongoTemplate.getCollection("entitlement_usage").insertOne(legacyBucket);
        trackDocumentId("entitlement_usage", bucketId);

        boolean replaced = store.replaceUsage(orgId, bucketId, 3, 2, null,
                Instant.now(), "admin-it");

        assertTrue(replaced, "CAS should succeed with null revision for legacy document");

        UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(updated);
        assertEquals(2, updated.used());
        assertEquals(1L, updated.revision());
    }

    @Test
    void realReservationPathIncrementsUsageAtomically() {
        setupStore();

        String orgId = generateTestId("org");

        String bucketId1 = entitlementService.reserve(orgId, UsageMetric.CERTIFICATE);
        trackDocumentId("entitlement_usage", bucketId1);

        UsageBucket bucket = mongoTemplate.findById(bucketId1, UsageBucket.class);
        assertNotNull(bucket);
        assertEquals(1, bucket.used());
        assertEquals(1L, bucket.revision());

        String bucketId2 = entitlementService.reserve(orgId, UsageMetric.CERTIFICATE);
        trackDocumentId("entitlement_usage", bucketId2);

        UsageBucket bucket2 = mongoTemplate.findById(bucketId2, UsageBucket.class);
        assertNotNull(bucket2);
        assertEquals(2, bucket2.used());
        assertEquals(2L, bucket2.revision());
    }

    @Test
    void concurrentReservationsAreSerializedByCas() throws ExecutionException, InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String bucketId = entitlementService.reserve(orgId, UsageMetric.CERTIFICATE);
                    trackDocumentId("entitlement_usage", bucketId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        executor.shutdown();

        assertEquals(30, successCount.get(), "All 30 reservations should succeed within FREE plan limit");
        assertEquals(0, failureCount.get(), "No reservations should fail within plan limit");

        UsageBucket bucket = mongoTemplate.findById(orgId + ":CERTIFICATE:" + 
                java.time.YearMonth.from(java.time.LocalDate.ofInstant(Instant.now(), java.time.ZoneOffset.UTC)).toString(), 
                UsageBucket.class);
        if (bucket != null) {
            trackDocumentId("entitlement_usage", bucket.id());
            assertEquals(30, bucket.used());
        }
    }

    @Test
    void concurrentReconcileOperationsAreSerializedByRevision() throws ExecutionException, InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                10,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);
        trackDocumentId("entitlement_usage", bucketId);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    for (int attempt = 0; attempt < 10; attempt++) {
                        UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                        if (current != null) {
                            boolean replaced = store.replaceUsage(orgId, bucketId,
                                    current.used(), 5, current.revision(),
                                    Instant.now(), "reconciler");
                            if (replaced) {
                                successCount.incrementAndGet();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Thread failed
                }
            }, executor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Only one reconcile should succeed");

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertEquals(5, finalBucket.used());
    }
}
