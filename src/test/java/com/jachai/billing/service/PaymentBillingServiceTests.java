package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentBillingServiceTests {
    private static final String ORDER_ID = "J123456789012345678901234";
    @Mock TenantAccessService access;
    @Mock EntitlementService entitlements;
    @Mock UserRepository users;
    @Mock PaymentOrderRepository orders;
    @Mock SubscriptionRepository subscriptions;
    @Mock SslCommerzSandboxGateway ssl;

    private PaymentBillingService service() {
        return new PaymentBillingService(access, entitlements, users, orders,
                subscriptions, ssl, new BigDecimal("100.00"));
    }

    @Test
    void startsOnlyConfiguredSandboxCheckoutForAdmin() {
        when(entitlements.plan("org")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));
        when(ssl.configured()).thenReturn(true);
        when(users.findById("admin")).thenReturn(Optional.of(admin()));
        when(orders.insert(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ssl.start(any(PaymentOrder.class), any(User.class)))
                .thenReturn(new SslCommerzSandboxGateway.CheckoutSession("session", "https://sandbox.sslcommerz.com/pay"));

        var checkout = service().start("admin", "org", PaymentProvider.SSLCOMMERZ);

        assertEquals(PaymentProvider.SSLCOMMERZ, checkout.provider());
        assertEquals(new BigDecimal("100.00"), checkout.amountBdt());
        verify(access).requireRole("admin", "org", OrganizationRole.ORG_ADMIN);
        verify(orders).save(any(PaymentOrder.class));
    }

    @Test
    void directBkashCannotStartUntilItsRealSandboxAdapterExists() {
        when(entitlements.plan("org")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));

        assertEquals("BILLING_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service().start("admin", "org", PaymentProvider.BKASH)).code());
        verify(orders, never()).insert(any(PaymentOrder.class));
    }

    @Test
    void verifiedSandboxPaymentAppliesOneTeamTermAndPersistsOrderHistory() {
        PaymentOrder ready = readyOrder();
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(ready));
        when(ssl.verify(ready, "validation1"))
                .thenReturn(new SslCommerzSandboxGateway.VerifiedPayment("validation1", "bank123"));
        when(orders.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service().verifySslCallback(ORDER_ID, "validation1");

        assertEquals(PaymentOrderStatus.APPLIED, view.status());
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptions).save(saved.capture());
        assertEquals(PlanTier.TEAM, saved.getValue().tier());
        assertTrue(saved.getValue().appliedOrderIds().contains(ORDER_ID));
        assertTrue(saved.getValue().currentPeriodEnd().isAfter(Instant.now().plusSeconds(29 * 86400L)));
    }

    @Test
    void duplicateCallbackCannotExtendPlanAgain() {
        PaymentOrder paid = readyOrder().verified("validation1", "bank123", Instant.now())
                .applied(Instant.now());
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(paid));

        assertEquals(PaymentOrderStatus.APPLIED,
                service().verifySslCallback(ORDER_ID, "validation1").status());
        verify(subscriptions, never()).save(any(Subscription.class));
        verify(ssl, never()).verify(any(PaymentOrder.class), any(String.class));
    }

    @Test
    void callbackAfterSubscriptionWriteButBeforeOrderUpdateDoesNotExtendAgain() {
        PaymentOrder verified = readyOrder().verified("validation1", "bank123", Instant.now());
        Subscription alreadyCredited = new Subscription("org", PlanTier.TEAM,
                com.jachai.billing.model.SubscriptionStatus.ACTIVE, ORDER_ID,
                Instant.now().plusSeconds(30 * 86400L), Instant.now(),
                java.util.List.of(ORDER_ID), 2L);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(verified));
        when(subscriptions.findById("org")).thenReturn(Optional.of(alreadyCredited));
        when(orders.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(PaymentOrderStatus.APPLIED,
                service().verifySslCallback(ORDER_ID, "validation1").status());
        verify(subscriptions, never()).save(any(Subscription.class));
        verify(ssl, never()).verify(any(PaymentOrder.class), any(String.class));
    }

    @Test
    void mismatchedReplayReferenceIsRejected() {
        PaymentOrder paid = readyOrder().verified("validation1", "bank123", Instant.now())
                .applied(Instant.now());
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(paid));

        assertEquals("INVALID_PAYMENT_CALLBACK", assertThrows(ApiException.class,
                () -> service().verifySslCallback(ORDER_ID, "attacker-val")).code());
    }

    private User admin() {
        return new User("admin", "admin@example.com", "Admin", "hash", true, false, 0, Instant.now());
    }

    private PaymentOrder readyOrder() {
        return new PaymentOrder(ORDER_ID, "org", "admin", PaymentProvider.SSLCOMMERZ,
                PaymentOrderStatus.CHECKOUT_READY, new BigDecimal("100.00"), "session",
                null, null, Instant.now(), null, null, 1L);
    }
}
