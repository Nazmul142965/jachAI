package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
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
    void reservesPublishedCapacityAtomicallyWithPlanLimit() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                org.mockito.ArgumentMatchers.eq(UsageBucket.class))).thenReturn(new UsageBucket(
                        "org:PUBLISHED_ASSESSMENT:all", "org", UsageMetric.PUBLISHED_ASSESSMENT,
                        "all", 1, Instant.now(), null));

        String id = service().reserve("org", UsageMetric.PUBLISHED_ASSESSMENT);

        assertEquals("org:PUBLISHED_ASSESSMENT:all", id);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class),
                any(FindAndModifyOptions.class), org.mockito.ArgumentMatchers.eq(UsageBucket.class));
        Document filter = query.getValue().getQueryObject();
        assertEquals(3L, ((Document) filter.get("used")).get("$lt"));
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
}
