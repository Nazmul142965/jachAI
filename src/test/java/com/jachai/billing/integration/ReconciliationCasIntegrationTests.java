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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

class ReconciliationCasIntegrationTests extends BaseMongoIntegrationTest {

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
    void reconciliationBackfillWithRevisionCas() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        String certId1 = generateTestId("cert");
        String certId2 = generateTestId("cert");

        mongoTemplate.getCollection("certificates").insertMany(List.of(
                new Document("_id", certId1)
                        .append("organizationId", orgId)
                        .append("issuedAt", Instant.now()),
                new Document("_id", certId2)
                        .append("organizationId", orgId)
                        .append("issuedAt", Instant.now())
        ));
        trackDocumentId("certificates", certId1);
        trackDocumentId("certificates", certId2);

        UsageBucket bucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                "2026-10",
                1,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(bucket);
        trackDocumentId("entitlement_usage", bucketId);

        long actualCount = store.total("certificates", orgId);
        assertEquals(2, actualCount);

        boolean replaced = store.replaceUsage(orgId, bucketId, 1, 2, 1L,
                Instant.now(), "reconciler-it");

        assertTrue(replaced, "Reconciliation should succeed with matching revision");

        UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(updated);
        assertEquals(2, updated.used());
        assertEquals(2L, updated.revision());
        assertNotNull(updated.lastReconciledAt());
        assertEquals("reconciler-it", updated.reconciledByUserId());
    }

    @Test
    void concurrentReconcileOperationsPreventLostUpdates() throws InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        String assignId1 = generateTestId("assign");
        String assignId2 = generateTestId("assign");
        String assignId3 = generateTestId("assign");

        mongoTemplate.getCollection("assignments").insertMany(List.of(
                new Document("_id", assignId1)
                        .append("organizationId", orgId)
                        .append("createdAt", Instant.now())
                        .append("campaignId", null),
                new Document("_id", assignId2)
                        .append("organizationId", orgId)
                        .append("createdAt", Instant.now())
                        .append("campaignId", null),
                new Document("_id", assignId3)
                        .append("organizationId", orgId)
                        .append("createdAt", Instant.now())
                        .append("campaignId", null)
        ));
        trackDocumentId("assignments", assignId1);
        trackDocumentId("assignments", assignId2);
        trackDocumentId("assignments", assignId3);

        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);
        trackDocumentId("entitlement_usage", bucketId);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Long> finalRevisions = new ArrayList<>();
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int attempt = 0; attempt < 10; attempt++) {
                        UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                        if (current != null) {
                            long actualCount = store.total("assignments", orgId);
                            boolean replaced = store.replaceUsage(orgId, bucketId,
                                    current.used(), actualCount, current.revision(),
                                    Instant.now(), "reconciler-" + threadIndex);
                            if (replaced) {
                                successCount.incrementAndGet();
                                UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
                                if (updated != null) {
                                    synchronized (finalRevisions) {
                                        finalRevisions.add(updated.revision());
                                    }
                                }
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
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
        assertEquals(3, finalBucket.used(), "Final usage should match actual count");
        assertEquals(1, successCount.get(), "Exactly one reconcile should succeed");
        assertTrue(finalBucket.revision() > 1L, "Revision should have incremented");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);

        synchronized (finalRevisions) {
            assertEquals(1, finalRevisions.stream().distinct().count(),
                    "All successful writes should have converged to the same final revision");
        }
    }

    @Test
    void reconciliationWithConcurrentReservation() throws InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        String certId1 = generateTestId("cert");
        String certId2 = generateTestId("cert");

        mongoTemplate.getCollection("certificates").insertMany(List.of(
                new Document("_id", certId1)
                        .append("organizationId", orgId)
                        .append("issuedAt", Instant.now()),
                new Document("_id", certId2)
                        .append("organizationId", orgId)
                        .append("issuedAt", Instant.now())
        ));
        trackDocumentId("certificates", certId1);
        trackDocumentId("certificates", certId2);

        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                "2026-10",
                2,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);
        trackDocumentId("entitlement_usage", bucketId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger reconcileSuccess = new AtomicInteger(0);
        AtomicInteger reserveSuccess = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        executor.submit(() -> {
            try {
                startLatch.await();
                for (int attempt = 0; attempt < 10; attempt++) {
                    UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                    if (current != null) {
                        boolean replaced = store.replaceUsage(orgId, bucketId,
                                current.used(), 2, current.revision(),
                                Instant.now(), "reconciler");
                        if (replaced) {
                            reconcileSuccess.incrementAndGet();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                synchronized (exceptions) {
                    exceptions.add(e);
                }
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                for (int attempt = 0; attempt < 10; attempt++) {
                    UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                    if (current != null && current.used() < 10) {
                        boolean inserted = store.insertBucket(new UsageBucket(
                                bucketId,
                                orgId,
                                UsageMetric.CERTIFICATE,
                                "2026-10",
                                current.used() + 1,
                                current.createdAt(),
                                current.expiresAt(),
                                Instant.now()
                        ));
                        if (inserted) {
                            reserveSuccess.incrementAndGet();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                synchronized (exceptions) {
                    exceptions.add(e);
                }
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Both threads should complete");
        executor.shutdown();

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertTrue(finalBucket.used() >= 2, "Usage should not decrease below actual count");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
    }

    @Test
    void sameMillisecondAbaPreventedByMonotonicRevision() throws InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":PUBLISHED_ASSESSMENT:all";

        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.PUBLISHED_ASSESSMENT,
                "all",
                1,
                Instant.now(),
                null,
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);
        trackDocumentId("entitlement_usage", bucketId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int attempt = 0; attempt < 10; attempt++) {
                        UsageBucket current = mongoTemplate.findById(bucketId, UsageBucket.class);
                        if (current != null) {
                            boolean replaced = store.replaceUsage(orgId, bucketId,
                                    current.used(), 2, current.revision(),
                                    Instant.now(), "reconciler-" + threadIndex);
                            if (replaced) {
                                successCount.incrementAndGet();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Both threads should complete");
        executor.shutdown();

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertEquals(1, successCount.get(), "Only one operation should succeed");
        assertTrue(finalBucket.revision() > 1L, "Revision should have incremented");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
    }

    @Test
    void reconciliationIdempotencyWithRevision() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        String assignId1 = generateTestId("assign");

        mongoTemplate.getCollection("assignments").insertMany(List.of(
                new Document("_id", assignId1)
                        .append("organizationId", orgId)
                        .append("createdAt", Instant.now())
                        .append("campaignId", null)
        ));
        trackDocumentId("assignments", assignId1);

        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.INVITATION,
                "2026-10",
                1,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);
        trackDocumentId("entitlement_usage", bucketId);

        boolean firstReplace = store.replaceUsage(orgId, bucketId, 1, 1, 1L,
                Instant.now(), "reconciler");
        assertTrue(firstReplace);

        UsageBucket afterFirst = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(afterFirst);
        assertEquals(2L, afterFirst.revision());

        boolean secondReplace = store.replaceUsage(orgId, bucketId, 1, 1, 1L,
                Instant.now(), "reconciler");
        assertFalse(secondReplace, "Second replace with old revision should fail");

        UsageBucket afterSecond = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(afterSecond);
        assertEquals(2L, afterSecond.revision(), "Revision should not change on failed CAS");
    }

    @Test
    void reconciliationWithLegacyDocumentWithoutRevision() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        Document legacyBucket = new Document("_id", bucketId)
                .append("organizationId", orgId)
                .append("metric", "CERTIFICATE")
                .append("window", "2026-10")
                .append("used", 1)
                .append("createdAt", Instant.now())
                .append("expiresAt", Instant.now().plusSeconds(86400))
                .append("lastChangedAt", Instant.now());

        mongoTemplate.getCollection("entitlement_usage").insertOne(legacyBucket);
        trackDocumentId("entitlement_usage", bucketId);

        boolean replaced = store.replaceUsage(orgId, bucketId, 1, 2, null,
                Instant.now(), "reconciler-it");

        assertTrue(replaced, "Reconciliation should succeed with null revision for legacy document");

        UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(updated);
        assertEquals(2, updated.used());
        assertEquals(1L, updated.revision(), "Legacy document should be initialized to revision 1");
    }
}
