package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PlatformBillingSupportServiceTests {
    @Mock PlatformAdminService platform;
    @Mock OrganizationRepository organizations;
    @Mock SubscriptionRepository subscriptions;
    @Mock PaymentOrderRepository orders;

    private PlatformBillingSupportService service() {
        return new PlatformBillingSupportService(platform, organizations, subscriptions, orders);
    }

    @Test
    void ordinaryUserCannotInspectAnotherTenantsBilling() {
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service().billing("ordinary", "foreign-org")).code());
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service().orders("ordinary", "foreign-org", 0)).code());
        verify(organizations, never()).existsById(any());
        verify(subscriptions, never()).findById(any());
        verify(orders, never()).findByOrganizationId(any(), any());
    }

    @Test
    void unknownOrganizationDoesNotLeakSubscriptionOrOrders() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().billing("admin", "missing")).code());
        verify(subscriptions, never()).findById(any());
        verify(orders, never()).findByOrganizationId(any(), any());
    }

    @Test
    void missingSubscriptionIsReportedAsAbsentNotPaid() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(organizations.existsById("org")).thenReturn(true);

        var view = service().billing("admin", "org");

        assertFalse(view.subscriptionPresent());
        assertNull(view.tier());
        assertNull(view.status());
    }

    @Test
    void platformSupportCanReadPlanStateWithoutMutation() {
        Instant expiry = Instant.parse("2026-12-01T00:00:00Z");
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(organizations.existsById("org")).thenReturn(true);
        when(subscriptions.findById("org")).thenReturn(Optional.of(new Subscription("org", PlanTier.TEAM,
                SubscriptionStatus.ACTIVE, "paid-order", expiry, expiry.minusSeconds(100), List.of("paid-order"), 1L)));

        var view = service().billing("admin", "org");

        assertTrue(view.subscriptionPresent());
        assertEquals(PlanTier.TEAM, view.tier());
        assertEquals("paid-order", view.lastPaidOrderId());
        verify(subscriptions, never()).save(any());
    }

    @Test
    void paymentHistoryIsTenantScopedAndHidesGatewayIdentifiers() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(organizations.existsById("org")).thenReturn(true);
        PaymentOrder order = new PaymentOrder("order", "org", "buyer", PaymentProvider.BKASH,
                PaymentOrderStatus.APPLIED, new BigDecimal("100.00"), "session-secret", "validation-secret",
                "transaction-secret", Instant.parse("2026-01-01T00:00:00Z"), null, null, 1L);
        PageRequest request = PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(orders.findByOrganizationId("org", request)).thenReturn(new PageImpl<>(List.of(order)));

        var history = service().orders("admin", "org", 2);

        assertEquals("order", history.getContent().getFirst().id());
        assertEquals(7, history.getContent().getFirst().getClass().getRecordComponents().length);
        verify(orders).findByOrganizationId("org", request);
        verify(orders, never()).findAll();
    }

    @Test
    void excessivePageIsRejectedBeforeDatabaseQuery() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(organizations.existsById("org")).thenReturn(true);

        assertEquals("INVALID_PAGE", assertThrows(ApiException.class,
                () -> service().orders("admin", "org", 501)).code());
        verify(orders, never()).findByOrganizationId(any(), any());
    }
}
