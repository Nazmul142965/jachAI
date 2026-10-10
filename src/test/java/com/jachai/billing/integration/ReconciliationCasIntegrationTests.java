package com.jachai.billing.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.repository.MongoEntitlementUsageStore;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.billing.service.EntitlementService;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.platform.repository.PlatformPlanEventRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class ReconciliationCasIntegrationTests extends BaseMongoIntegrationTest {

    private MongoTemplate mongoTemplate;
    private MongoEntitlementUsageStore store;
    private EntitlementService entitlementService;

    /** The window the application itself derives for a monthly metric, so reservations hit our fixture. */
    private static String currentWindow() {
        return YearMonth.now(ZoneOffset.UTC).toString();
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

        // The real reservation path is exercised here, so EntitlementService is built around the same
        // MongoTemplate; only the subscription lookup (which resolves the FREE plan) and tenant access
        // are mocks, because reserve() does not consult tenant access.
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        TenantAccessService access = new TenantAccessService(mock(MembershipRepository.class), organizations);
        entitlementService = new EntitlementService(subscriptions, mongoTemplate, access,
                mock(PlatformPlanEventRepository.class));
        when(organizations.findById(any())).thenReturn(Optional.of(
                new Organization("test-org", "Test Organization", OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    @Test
    void reconciliationBackfillWithRevisionCas() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":CERTIFICATE:2026-10";

        String certId1 = generateTestId("cert");
        String certId2 = generateTestId("cert");

        // Register every ID before its write, so an interruption cannot leave an untracked document.
        trackDocumentId("certificates", certId1);
        trackDocumentId("certificates", certId2);
        trackDocumentId("entitlement_usage", bucketId);

        mongoTemplate.getCollection("certificates").insertMany(List.of(
                new Document("_id", certId1)
                        .append("organizationId", orgId)
                        .append("attemptId", certId1)
                        .append("verificationCode", certId1)
                        .append("issuedAt", Instant.now()),
                new Document("_id", certId2)
                        .append("organizationId", orgId)
                        .append("attemptId", certId2)
                        .append("verificationCode", certId2)
                        .append("issuedAt", Instant.now())
        ));

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

        trackDocumentId("assignments", assignId1);
        trackDocumentId("assignments", assignId2);
        trackDocumentId("assignments", assignId3);
        trackDocumentId("entitlement_usage", bucketId);

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

        long actualCount = store.total("assignments", orgId);
        assertEquals(3, actualCount, "Fixture should record three assignments");
        long startingUsage = 1L;
        long startingRevision = 1L;

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // One attempt each against the same starting revision: only one CAS can match, which
                    // is what makes "exactly one winner" a provable assertion. Retrying after a re-read
                    // would legitimately succeed again, because replaceUsage does not reject a no-op.
                    boolean replaced = store.replaceUsage(orgId, bucketId, startingUsage, actualCount,
                            startingRevision, Instant.now(), "reconciler-" + threadIndex);
                    if (replaced) {
                        successCount.incrementAndGet();
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
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All workers should complete");
        executor.shutdown();

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertEquals(1, successCount.get(), "Exactly one CAS against the starting revision can match");
        assertEquals(actualCount, finalBucket.used(), "The winner should apply the real assignment count");
        assertEquals(startingRevision + 1L, finalBucket.revision(),
                "The winner should advance the revision exactly once");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
    }

    @Test
    void staleReconciliationCannotEraseConcurrentReservation() throws InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        String window = currentWindow();
        String bucketId = orgId + ":CERTIFICATE:" + window;

        String certId1 = generateTestId("cert");
        String certId2 = generateTestId("cert");

        trackDocumentId("certificates", certId1);
        trackDocumentId("certificates", certId2);
        trackDocumentId("entitlement_usage", bucketId);

        mongoTemplate.getCollection("certificates").insertMany(List.of(
                new Document("_id", certId1)
                        .append("organizationId", orgId)
                        .append("attemptId", certId1)
                        .append("verificationCode", certId1)
                        .append("issuedAt", Instant.now()),
                new Document("_id", certId2)
                        .append("organizationId", orgId)
                        .append("attemptId", certId2)
                        .append("verificationCode", certId2)
                        .append("issuedAt", Instant.now())
        ));

        // The bucket reflects both durable certificates before the reservation starts.
        UsageBucket initialBucket = new UsageBucket(
                bucketId,
                orgId,
                UsageMetric.CERTIFICATE,
                window,
                2,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                Instant.now()
        );

        mongoTemplate.insert(initialBucket);

        long recordedCertificates = store.total("certificates", orgId);
        assertEquals(2, recordedCertificates, "Fixture should record two certificates");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch reservationFinished = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger reconcileSuccess = new AtomicInteger(0);
        AtomicInteger reserveSuccess = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        executor.submit(() -> {
            try {
                UsageBucket snapshot = mongoTemplate.findById(bucketId, UsageBucket.class);
                if (snapshot == null) throw new IllegalStateException("Usage bucket disappeared");
                snapshotTaken.countDown();
                if (!reservationFinished.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Reservation did not finish");
                }
                if (store.replaceUsage(orgId, bucketId, snapshot.used(), recordedCertificates,
                        snapshot.revision(), Instant.now(), "reconciler")) {
                    reconcileSuccess.incrementAndGet();
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
                if (!snapshotTaken.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Reconciliation did not take a snapshot");
                }
                String reservedBucketId = entitlementService.reserve(orgId, UsageMetric.CERTIFICATE);
                if (!bucketId.equals(reservedBucketId)) {
                    throw new IllegalStateException("Reservation moved to another UTC month");
                }
                trackDocumentId("entitlement_usage", reservedBucketId);
                reserveSuccess.incrementAndGet();
            } catch (Exception e) {
                synchronized (exceptions) {
                    exceptions.add(e);
                }
            } finally {
                reservationFinished.countDown();
                doneLatch.countDown();
            }
        });

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Both workers should complete");
        executor.shutdown();

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertEquals(1, reserveSuccess.get(),
                "The reservation must actually succeed through EntitlementService.reserve");
        assertEquals(0, reconcileSuccess.get(), "A stale reconciliation snapshot must lose the CAS");
        assertEquals(recordedCertificates + 1, finalBucket.used(),
                "The reservation must remain above the two durable certificate charges");
        assertEquals(2L, finalBucket.revision(), "Only the successful reservation advances the revision");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
    }

    @Test
    void sameMillisecondAbaPreventedByMonotonicRevision() {
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

        trackDocumentId("entitlement_usage", bucketId);
        mongoTemplate.insert(initialBucket);

        // All three writes share one timestamp, so the protection under test cannot come from time.
        Instant sameInstant = Instant.now();
        assertTrue(store.replaceUsage(orgId, bucketId, 1, 5, 1L, sameInstant, "reconciler-a"),
                "First write should match revision 1");
        assertTrue(store.replaceUsage(orgId, bucketId, 5, 1, 2L, sameInstant, "reconciler-b"),
                "Second write should restore the original usage under revision 2");

        UsageBucket afterAba = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(afterAba);
        assertEquals(1, afterAba.used(), "Usage is back to its original value, which is the ABA condition");
        assertEquals(3L, afterAba.revision(), "Revision must stay monotonic across both writes");

        assertFalse(store.replaceUsage(orgId, bucketId, 1, 2, 1L, sameInstant, "reconciler-stale"),
                "A stale revision must not match after usage returned to its original value");

        UsageBucket unchanged = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(unchanged);
        assertEquals(1, unchanged.used(), "The rejected stale write must not change usage");
        assertEquals(3L, unchanged.revision(), "The rejected stale write must not advance the revision");
    }

    @Test
    void reconciliationIdempotencyWithRevision() {
        setupStore();

        String orgId = generateTestId("org");
        String bucketId = orgId + ":INVITATION:2026-10";

        String assignId1 = generateTestId("assign");

        trackDocumentId("assignments", assignId1);
        trackDocumentId("entitlement_usage", bucketId);

        mongoTemplate.getCollection("assignments").insertMany(List.of(
                new Document("_id", assignId1)
                        .append("organizationId", orgId)
                        .append("createdAt", Instant.now())
                        .append("campaignId", null)
        ));

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

        trackDocumentId("entitlement_usage", bucketId);
        mongoTemplate.getCollection("entitlement_usage").insertOne(legacyBucket);

        boolean replaced = store.replaceUsage(orgId, bucketId, 1, 2, null,
                Instant.now(), "reconciler-it");

        assertTrue(replaced, "Reconciliation should succeed with null revision for legacy document");

        UsageBucket updated = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(updated);
        assertEquals(2, updated.used());
        assertEquals(1L, updated.revision(), "Legacy document should be initialized to revision 1");
    }
}
