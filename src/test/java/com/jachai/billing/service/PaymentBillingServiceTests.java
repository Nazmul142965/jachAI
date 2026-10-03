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
    @Mock UserRepository users;
    @Mock PaymentOrderRepository orders;
    @Mock SubscriptionRepository subscriptions;
    @Mock SslCommerzSandboxGateway ssl;
    @Mock BkashSandboxGateway bkash;

    private PaymentBillingService service() {
        return new PaymentBillingService(access, users, orders,
                subscriptions, ssl, bkash, new BigDecimal("100.00"));
    }

    @Test
    void startsOnlyConfiguredSandboxCheckoutForAdmin() {
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
    void directBkashCannotStartWithoutSandboxCredentials() {

        assertEquals("BILLING_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service().start("admin", "org", PaymentProvider.BKASH)).code());
        verify(orders, never()).insert(any(PaymentOrder.class));
    }

    @Test
    void startsDirectBkashSandboxCheckoutWhenConfigured() {
        when(bkash.configured()).thenReturn(true);
        when(users.findById("admin")).thenReturn(Optional.of(admin()));
        when(orders.insert(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bkash.start(any(PaymentOrder.class)))
                .thenReturn(new BkashSandboxGateway.CheckoutSession("payment12345",
                        "https://sandbox.payment.bkash.com/pay"));

        var checkout = service().start("admin", "org", PaymentProvider.BKASH);

        assertEquals(PaymentProvider.BKASH, checkout.provider());
        verify(orders).save(any(PaymentOrder.class));
    }

    @Test
    void verifiedBkashPaymentAppliesTeamOnce() {
        PaymentOrder ready = new PaymentOrder(ORDER_ID, "org", "admin", PaymentProvider.BKASH,
                PaymentOrderStatus.CHECKOUT_READY, new BigDecimal("100.00"), "payment12345",
                null, null, Instant.now(), null, null, 1L);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(ready));
        when(bkash.verify(ready, "payment12345"))
                .thenReturn(new BkashSandboxGateway.VerifiedPayment("payment12345", "trx123"));
        when(orders.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(PaymentOrderStatus.APPLIED,
                service().verifyBkashCallback(ORDER_ID, "payment12345").status());
        verify(subscriptions).save(any(Subscription.class));
    }

    @Test
    void earlyManualRenewalExtendsFromExistingPeriodEnd() {
        PaymentOrder ready = new PaymentOrder(ORDER_ID, "org", "admin", PaymentProvider.BKASH,
                PaymentOrderStatus.CHECKOUT_READY, new BigDecimal("100.00"), "payment12345",
                null, null, Instant.now(), null, null, 1L);
        Instant previousEnd = Instant.now().plusSeconds(15 * 86400L);
        Subscription active = new Subscription("org", PlanTier.TEAM,
                com.jachai.billing.model.SubscriptionStatus.ACTIVE, "oldOrder", previousEnd,
                Instant.now(), java.util.List.of("oldOrder"), 1L);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(ready));
        when(bkash.verify(ready, "payment12345"))
                .thenReturn(new BkashSandboxGateway.VerifiedPayment("payment12345", "trx123"));
        when(orders.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptions.findById("org")).thenReturn(Optional.of(active));

        service().verifyBkashCallback(ORDER_ID, "payment12345");

        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptions).save(saved.capture());
        assertEquals(previousEnd.plusSeconds(30 * 86400L), saved.getValue().currentPeriodEnd());
        assertEquals(java.util.List.of("oldOrder", ORDER_ID), saved.getValue().appliedOrderIds());
    }

    @Test
    void reconciliationCannotReadAnotherOrganizationsOrder() {
        when(orders.findByIdAndOrganizationId(ORDER_ID, "other-org")).thenReturn(Optional.empty());

        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().reconcile("admin", "other-org", ORDER_ID)).code());
        verify(access).requireRole("admin", "other-org", OrganizationRole.ORG_ADMIN);
        verify(orders, never()).findById(ORDER_ID);
    }

    @Test
    void sslCheckoutCannotBeGrantedByAdminReconciliationWithoutGatewayReference() {
        when(orders.findByIdAndOrganizationId(ORDER_ID, "org")).thenReturn(Optional.of(readyOrder()));
        when(ssl.findValidationId(any(PaymentOrder.class))).thenReturn(Optional.empty());

        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> service().reconcile("admin", "org", ORDER_ID)).code());
        verify(subscriptions, never()).save(any(Subscription.class));
    }

    @Test
    void adminCanRecoverCompletedSslPaymentAfterLostCallbackOnlyAfterValidation() {
        PaymentOrder ready = readyOrder();
        when(orders.findByIdAndOrganizationId(ORDER_ID, "org")).thenReturn(Optional.of(ready));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(ready));
        when(ssl.findValidationId(ready)).thenReturn(Optional.of("validation1"));
        when(ssl.verify(ready, "validation1"))
                .thenReturn(new SslCommerzSandboxGateway.VerifiedPayment("validation1", "bank123"));
        when(orders.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(PaymentOrderStatus.APPLIED, service().reconcile("admin", "org", ORDER_ID).status());
        verify(ssl).verify(ready, "validation1");
        verify(subscriptions).save(any(Subscription.class));
    }

    @Test
    void sslQueryReferenceAloneCannotActivateTeam() {
        PaymentOrder ready = readyOrder();
        when(orders.findByIdAndOrganizationId(ORDER_ID, "org")).thenReturn(Optional.of(ready));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(ready));
        when(ssl.findValidationId(ready)).thenReturn(Optional.of("validation1"));
        when(ssl.verify(ready, "validation1"))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "PAYMENT_NOT_VERIFIED", "Payment was not verified"));

        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> service().reconcile("admin", "org", ORDER_ID)).code());
        verify(orders, never()).save(any(PaymentOrder.class));
        verify(subscriptions, never()).save(any(Subscription.class));
    }

    @Test
    void adminCanRecoverCompletedBkashPaymentAfterLostBrowserCallback() {
        PaymentOrder ready = new PaymentOrder(ORDER_ID, "org", "admin", PaymentProvider.BKASH,
                PaymentOrderStatus.CHECKOUT_READY, new BigDecimal("100.00"), "payment12345",
                null, null, Instant.now(), null, null, 1L);
        when(orders.findByIdAndOrganizationId(ORDER_ID, "org")).thenReturn(Optional.of(ready));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(ready));
        when(bkash.verify(ready, "payment12345"))
                .thenReturn(new BkashSandboxGateway.VerifiedPayment("payment12345", "trx123"));
        when(orders.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(PaymentOrderStatus.APPLIED,
                service().reconcile("admin", "org", ORDER_ID).status());
        verify(subscriptions).save(any(Subscription.class));
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
