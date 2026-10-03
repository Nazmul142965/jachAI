package com.jachai.billing.integration;

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

class EntitlementReservationCasIntegrationTests extends BaseMongoIntegrationTest {

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
        entitlementService = new EntitlementService(subscriptions, mongoTemplate, access);
        when(organizations.findById(any())).thenReturn(Optional.of(
                new Organization("test-org", "Test Organization", OrganizationType.ACADEMY, "owner", Instant.now())));
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

        trackDocumentId("entitlement_usage", bucketId);
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

        trackDocumentId("entitlement_usage", bucketId);
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

        trackDocumentId("entitlement_usage", bucketId);
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

        trackDocumentId("entitlement_usage", bucketId);
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

        trackDocumentId("entitlement_usage", bucketId);
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
    void realReservationPathIncrementsUsageAtomically() {
        setupStore();

        String orgId = generateTestId("org");
        String expectedBucketId = orgId + ":CERTIFICATE:" + currentWindow();
        trackDocumentId("entitlement_usage", expectedBucketId);

        String bucketId1 = entitlementService.reserve(orgId, UsageMetric.CERTIFICATE);
        trackDocumentId("entitlement_usage", bucketId1);
        assertEquals(expectedBucketId, bucketId1, "Reservation should use the current UTC month window");

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
    void concurrentReservationsAreSerializedByCas() throws InterruptedException {
        setupStore();

        String orgId = generateTestId("org");
        String expectedBucketId = orgId + ":CERTIFICATE:" + currentWindow();
        trackDocumentId("entitlement_usage", expectedBucketId);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String bucketId = entitlementService.reserve(orgId, UsageMetric.CERTIFICATE);
                    trackDocumentId("entitlement_usage", bucketId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
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

        assertEquals(threadCount, successCount.get(),
                "All " + threadCount + " reservations should succeed within the FREE plan certificate limit");
        assertEquals(0, failureCount.get(), "No reservations should fail within the plan limit");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);

        UsageBucket bucket = mongoTemplate.findById(expectedBucketId, UsageBucket.class);
        assertNotNull(bucket, "Bucket should exist after reservations");
        assertEquals(threadCount, bucket.used(), "Bucket should record every reservation exactly once");
        assertEquals(threadCount, bucket.revision(), "Each reservation should advance the revision once");
    }

    @Test
    void concurrentReconcileOperationsAreSerializedByRevision() throws InterruptedException {
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

        trackDocumentId("entitlement_usage", bucketId);
        mongoTemplate.insert(initialBucket);

        long startingUsage = 10L;
        long startingRevision = 1L;
        long reconciledUsage = 5L;

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
                    // One attempt each against the same starting revision: exactly one can match. A retry
                    // loop would legitimately succeed again, because replaceUsage accepts a no-op update.
                    boolean replaced = store.replaceUsage(orgId, bucketId, startingUsage, reconciledUsage,
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
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one CAS against the starting revision can match");
        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);

        UsageBucket finalBucket = mongoTemplate.findById(bucketId, UsageBucket.class);
        assertNotNull(finalBucket);
        assertEquals(reconciledUsage, finalBucket.used(), "The winner should apply the reconciled usage");
        assertEquals(startingRevision + 1L, finalBucket.revision(),
                "The winner should advance the revision exactly once");
    }
}
