package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.model.PlatformPlanAction;
import com.jachai.platform.model.PlatformPlanEvent;
import com.jachai.platform.repository.PlatformPlanEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class PlatformPlanServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    @Mock PlatformAdminService platform;
    @Mock OrganizationRepository organizations;
    @Mock SubscriptionRepository subscriptions;
    @Mock PlatformPlanEventRepository events;

    private PlatformPlanService service() {
        return new PlatformPlanService(platform, organizations, subscriptions, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void adminCanAccess() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(organizations.existsById("org")).thenReturn(true);
    }

    @Test
    void ordinaryUserCannotInspectOrGrantAnotherTenantsPlan() {
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service().current("ordinary", "org")).code());
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service().grant("ordinary", "org", NOW.plusSeconds(3600), "Support trial grant")).code());
        verify(organizations, never()).existsById(any());
        verify(events, never()).insert(any(PlatformPlanEvent.class));
    }

    @Test
    void unknownTenantCannotReceiveGrant() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().grant("admin", "missing", NOW.plusSeconds(3600), "Support trial grant")).code());
        verify(events, never()).insert(any(PlatformPlanEvent.class));
    }

    @Test
    void boundedGrantIsAnImmutableAuditedEvent() {
        adminCanAccess();
        when(events.insert(any(PlatformPlanEvent.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().grant("admin", "org", NOW.plusSeconds(86400), " Support trial grant ");

        assertTrue(view.active());
        assertEquals(PlatformPlanAction.GRANTED, view.latestAction());
        assertEquals(1, view.revision());
        org.mockito.ArgumentCaptor<PlatformPlanEvent> captured = org.mockito.ArgumentCaptor.forClass(PlatformPlanEvent.class);
        verify(events).insert(captured.capture());
        assertEquals("admin", captured.getValue().actorUserId());
        assertEquals("Support trial grant", captured.getValue().reason());
        assertEquals(NOW.plusSeconds(86400), captured.getValue().expiresAt());
    }

    @Test
    void exactRetryAfterMongoMillisecondRoundTripDoesNotAddAnotherGrant() {
        adminCanAccess();
        Instant requestedExpiry = NOW.plusSeconds(3600).plusNanos(987_654_321);
        PlatformPlanEvent stored = new PlatformPlanEvent("first", "org", "admin",
                PlatformPlanAction.GRANTED, requestedExpiry.truncatedTo(ChronoUnit.MILLIS),
                "Support trial grant", 1, NOW);
        when(events.findFirstByOrganizationIdOrderByRevisionDesc("org")).thenReturn(Optional.of(stored));

        var view = service().grant("admin", "org", requestedExpiry, "Support trial grant");

        assertEquals(1, view.revision());
        verify(events, never()).insert(any(PlatformPlanEvent.class));
    }

    @Test
    void paidSubscriptionCannotBeReplacedByManualGrant() {
        adminCanAccess();
        when(subscriptions.findById("org")).thenReturn(Optional.of(new Subscription("org", PlanTier.TEAM,
                SubscriptionStatus.ACTIVE, "paid-order", NOW.plusSeconds(3600), NOW, 1L)));

        assertEquals("PAID_PLAN_ACTIVE", assertThrows(ApiException.class,
                () -> service().grant("admin", "org", NOW.plusSeconds(7200), "Support trial grant")).code());
        verify(events, never()).insert(any(PlatformPlanEvent.class));
    }

    @Test
    void grantMustExpireWithinThirtyDays() {
        adminCanAccess();
        assertEquals("INVALID_GRANT_PERIOD", assertThrows(ApiException.class,
                () -> service().grant("admin", "org", NOW, "Support trial grant")).code());
        assertEquals("INVALID_GRANT_PERIOD", assertThrows(ApiException.class,
                () -> service().grant("admin", "org", NOW.plusSeconds(31L * 86400), "Support trial grant")).code());
        verify(events, never()).insert(any(PlatformPlanEvent.class));
    }

    @Test
    void revokeOnlyActiveManualGrantAndPreservesHistory() {
        adminCanAccess();
        PlatformPlanEvent grant = new PlatformPlanEvent("first", "org", "admin", PlatformPlanAction.GRANTED,
                NOW.plusSeconds(3600), "Support trial grant", 1, NOW.minusSeconds(60));
        when(events.findFirstByOrganizationIdOrderByRevisionDesc("org")).thenReturn(Optional.of(grant));
        when(events.insert(any(PlatformPlanEvent.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().revoke("admin", "org", "Trial ended early");

        assertFalse(view.active());
        assertEquals(2, view.revision());
        org.mockito.ArgumentCaptor<PlatformPlanEvent> captured = org.mockito.ArgumentCaptor.forClass(PlatformPlanEvent.class);
        verify(events).insert(captured.capture());
        assertEquals(PlatformPlanAction.REVOKED, captured.getValue().action());
        assertEquals(null, captured.getValue().expiresAt());
    }

    @Test
    void concurrentPlanChangeReturnsConflictInsteadOfOverwritingEvent() {
        adminCanAccess();
        when(events.insert(any(PlatformPlanEvent.class))).thenThrow(new DuplicateKeyException("revision collision"));

        assertEquals("PLAN_CHANGED", assertThrows(ApiException.class,
                () -> service().grant("admin", "org", NOW.plusSeconds(3600), "Support trial grant")).code());
    }
}
