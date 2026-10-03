package com.jachai.billing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.repository.MongoEntitlementUsageStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class EntitlementReservationCasIntegrationTests extends BaseMongoIntegrationTest {

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
    void replaceUsageWithRevisionCompareAndSwap() {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
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

        String orgId = "org-it-" + System.currentTimeMillis();
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

        String orgId = "org-it-" + System.currentTimeMillis();
        String wrongOrgId = "wrong-org-it-" + System.currentTimeMillis();
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

        String orgId = "org-it-" + System.currentTimeMillis();
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

        boolean replaced = store.replaceUsage(orgId, bucketId, 3, 2, null,
                Instant.now(), "admin-it");

        assertTrue(replaced, "CAS should succeed with null revision for legacy document");

        UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(updated);
        assertEquals(2, updated.used());
        assertEquals(1L, updated.revision());
    }

    @Test
    void concurrentReservationsAreSerializedByCas() throws InterruptedException {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                "2026-10",
                0,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int attempt = 0; attempt < 5; attempt++) {
                        UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                        if (current != null && current.used() < 10) {
                            long newUsage = current.used() + 1;
                            boolean replaced = store.replaceUsage(orgId, bucketId,
                                    current.used(), newUsage, current.revision(),
                                    Instant.now(), "thread-" + threadIndex);
                            if (replaced) {
                                successCount.incrementAndGet();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Thread failed
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertTrue(finalBucket.used() <= 10, "Usage should not exceed plan limit");
        assertTrue(successCount.get() > 0, "At least some reservations should succeed");
    }

    @Test
    void concurrentReconcileOperationsAreSerializedByRevision() throws InterruptedException {
        setupStore();

        String orgId = "org-it-" + System.currentTimeMillis();
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

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int attempt = 0; attempt < 5; attempt++) {
                        UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                        if (current != null) {
                            boolean replaced = store.replaceUsage(orgId, bucketId,
                                    current.used(), 5, current.revision(),
                                    Instant.now(), "reconciler-" + threadIndex);
                            if (replaced) {
                                successCount.incrementAndGet();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Thread failed
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertEquals(1, successCount.get(), "Only one reconcile should succeed");
        assertEquals(5, finalBucket.used());
    }
}
