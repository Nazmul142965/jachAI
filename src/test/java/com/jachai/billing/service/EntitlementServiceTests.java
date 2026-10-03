package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.model.UsageWindow;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTests {
    @Mock SubscriptionRepository subscriptions;
    @Mock MongoTemplate mongo;
    @Mock TenantAccessService access;

    private EntitlementService service() {
        return new EntitlementService(subscriptions, mongo, access);
    }

    @Test
    void missingSubscriptionUsesFreePlan() {
        assertEquals(PlanTier.FREE, service().plan("org").tier());
        assertEquals(3, service().plan("org").maxPublishedAssessments());
    }

    @Test
    void activeTeamPlanHasHigherLimitsButExpiredPlanFallsBack() {
        when(subscriptions.findById("org")).thenReturn(Optional.of(new Subscription(
                "org", PlanTier.TEAM, SubscriptionStatus.ACTIVE, "payment-order",
                Instant.now().plusSeconds(3600), Instant.now(), 1L)));
        assertEquals(PlanTier.TEAM, service().plan("org").tier());
        when(subscriptions.findById("org")).thenReturn(Optional.of(new Subscription(
                "org", PlanTier.TEAM, SubscriptionStatus.ACTIVE, "payment-order",
                Instant.now().minusSeconds(1), Instant.now(), 1L)));
        assertEquals(PlanTier.FREE, service().plan("org").tier());
    }

    @Test
    void eachMetricReservesAgainstItsOwnPlanLimit() {
        when(subscriptions.findById("org")).thenReturn(Optional.of(new Subscription(
                "org", PlanTier.TEAM, SubscriptionStatus.ACTIVE, "payment-order",
                Instant.now().plusSeconds(3600), Instant.now(), 1L)));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class)))
                .thenReturn(new UsageBucket("bucket", "org", UsageMetric.INVITATION, "2026-10",
                        1, Instant.now(), null, Instant.now()));

        service().reserve("org", UsageMetric.INVITATION);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        assertEquals(2000L, ((Document) query.getValue().getQueryObject().get("used")).get("$lt"));
    }

    @Test
    void reservesPublishedCapacityAtomicallyWithPlanLimit() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class))).thenReturn(new UsageBucket(
                        "org:PUBLISHED_ASSESSMENT:all", "org", UsageMetric.PUBLISHED_ASSESSMENT,
                        "all", 1, Instant.now(), null, Instant.now()));

        String id = service().reserve("org", UsageMetric.PUBLISHED_ASSESSMENT);

        assertEquals("org:PUBLISHED_ASSESSMENT:all", id);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class),
                any(FindAndModifyOptions.class), org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        Document filter = query.getValue().getQueryObject();
        assertEquals(3L, ((Document) filter.get("used")).get("$lt"));
    }

    @Test
    void everyReservationStampsActivitySoStrandedUnitsCanBeIdentified() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class))).thenReturn(new UsageBucket(
                        "org:CERTIFICATE:2026-10", "org", UsageMetric.CERTIFICATE, "2026-10",
                        1, Instant.now(), Instant.now().plusSeconds(1), Instant.now()));

        service().reserve("org", UsageMetric.CERTIFICATE);

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        assertTrue(update.getValue().getUpdateObject().get("$set") instanceof Document set);
        assertTrue(((Document) update.getValue().getUpdateObject().get("$set")).containsKey("lastChangedAt"));
        assertEquals(1L, ((Document) update.getValue().getUpdateObject().get("$inc")).get("revision"));
    }

    @Test
    void monthlyReservationWindowIsTheUtcMonthAndExpiresAfterFourteenMonths() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class))).thenReturn(new UsageBucket(
                        "org:INVITATION:2026-10", "org", UsageMetric.INVITATION, "2026-10",
                        1, Instant.now(), Instant.parse("2027-12-01T00:00:00Z"), Instant.now()));

        String id = service().reserve("org", UsageMetric.INVITATION);

        String window = YearMonth.from(java.time.LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC)).toString();
        assertEquals("org:INVITATION:" + window, id);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        Document insert = (Document) update.getValue().getUpdateObject().get("$setOnInsert");
        assertEquals(YearMonth.parse(window).atDay(1).plusMonths(14).atStartOfDay()
                .toInstant(ZoneOffset.UTC), insert.get("expiresAt"));
    }

    @Test
    void publishedAssessmentBucketNeverExpires() {
        UsageWindow window = UsageWindow.of("org", UsageMetric.PUBLISHED_ASSESSMENT, Instant.now());

        assertEquals("all", window.window());
        assertNull(window.expiresAt());
        assertFalse(window.monthly());
        assertFalse(window.closedAt(Instant.now().plusSeconds(86_400)));
    }

    @Test
    void duplicateBucketAtLimitIsReportedAsPlanLimit() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class)))
                .thenThrow(new DuplicateKeyException("full"));

        ApiException error = assertThrows(ApiException.class,
                () -> service().reserve("org", UsageMetric.INVITATION));

        assertEquals("PLAN_LIMIT_REACHED", error.code());
    }

    @Test
    void refundDecrementsOnlyPositiveUsage() {
        service().refund("org:INVITATION:2026-10");
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), any(Update.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        assertEquals("org:INVITATION:2026-10", query.getValue().getQueryObject().get("_id"));
    }

    @Test
    void refundAlsoStampsActivitySoAnInFlightReleaseIsVisible() {
        service().refund("org:INVITATION:2026-10");
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        assertTrue(((Document) update.getValue().getUpdateObject().get("$set")).containsKey("lastChangedAt"));
        assertEquals(-1L, ((Document) update.getValue().getUpdateObject().get("$inc")).get("used"));
        assertEquals(1L, ((Document) update.getValue().getUpdateObject().get("$inc")).get("revision"));
    }
}
