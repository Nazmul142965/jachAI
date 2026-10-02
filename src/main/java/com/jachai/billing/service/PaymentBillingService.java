package com.jachai.billing.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.billing.dto.CheckoutView;
import com.jachai.billing.dto.PaymentOrderView;
import com.jachai.billing.dto.PaymentOptionsView;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentBillingService {
    private static final Duration DEMO_TERM = Duration.ofDays(30);
    private static final int WRITE_RETRIES = 5;
    private final TenantAccessService access;
    private final UserRepository users;
    private final PaymentOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final SslCommerzSandboxGateway ssl;
    private final BkashSandboxGateway bkash;
    private final BigDecimal demoPrice;

    public PaymentBillingService(TenantAccessService access, UserRepository users,
                                 PaymentOrderRepository orders,
                                 SubscriptionRepository subscriptions, SslCommerzSandboxGateway ssl,
                                 BkashSandboxGateway bkash,
                                 @Value("${app.billing.demo-team-price-bdt}") BigDecimal demoPrice) {
        this.access = access;
        this.users = users;
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.ssl = ssl;
        this.bkash = bkash;
        this.demoPrice = demoPrice;
    }

    public CheckoutView start(String actorId, String organizationId, PaymentProvider provider) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        if (demoPrice.compareTo(new BigDecimal("10.00")) < 0
                || demoPrice.compareTo(new BigDecimal("500000.00")) > 0
                || demoPrice.scale() > 2) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_UNAVAILABLE",
                    "Sandbox demo price is invalid");
        }
        if (provider == null || provider == PaymentProvider.SSLCOMMERZ && !ssl.configured()
                || provider == PaymentProvider.BKASH && !bkash.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BILLING_UNAVAILABLE",
                    "Requested sandbox payment provider is not configured");
        }
        User buyer = users.findById(actorId)
                .filter(user -> user.emailVerified() && !user.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE",
                        "Account is unavailable for billing"));
        Instant now = Instant.now();
        String id = "J" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        PaymentOrder order = orders.insert(new PaymentOrder(id, organizationId, actorId, provider,
                PaymentOrderStatus.CREATED, demoPrice.setScale(2), null, null, null,
                now, null, null, null));
        if (provider == PaymentProvider.BKASH) {
            var session = bkash.start(order);
            orders.save(order.checkoutReady(session.paymentId()));
            return new CheckoutView(order.id(), provider, order.amountBdt(), session.checkoutUrl());
        }
        var session = ssl.start(order, buyer);
        orders.save(order.checkoutReady(session.sessionId()));
        return new CheckoutView(order.id(), provider, order.amountBdt(), session.checkoutUrl());
    }

    public PaymentOptionsView options(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        return new PaymentOptionsView(true, ssl.configured(), bkash.configured(), demoPrice);
    }

    public Page<PaymentOrderView> history(String actorId, String organizationId, int page) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        return orders.findByOrganizationId(organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(PaymentOrderView::from);
    }

    public PaymentOrderView order(String actorId, String organizationId, String orderId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        return orders.findByIdAndOrganizationId(orderId, organizationId)
                .map(PaymentOrderView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Payment order not found"));
    }

    public PaymentOrderView verifySslCallback(String orderId, String validationId) {
        return verifyCallback(orderId, validationId, PaymentProvider.SSLCOMMERZ);
    }

    public PaymentOrderView verifyBkashCallback(String orderId, String paymentId) {
        return verifyCallback(orderId, paymentId, PaymentProvider.BKASH);
    }

    private PaymentOrderView verifyCallback(String orderId, String validationId,
                                            PaymentProvider provider) {
        if (orderId == null || !orderId.matches("J[0-9a-f]{24}")
                || validationId == null || validationId.isBlank()) {
            throw invalidCallback();
        }
        for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
            PaymentOrder order = orders.findById(orderId).orElseThrow(this::invalidCallback);
            if (order.provider() != provider) throw invalidCallback();
            if (order.status() == PaymentOrderStatus.APPLIED) {
                if (!validationId.equals(order.gatewayValidationId())) throw invalidCallback();
                return PaymentOrderView.from(order);
            }
            if (order.status() == PaymentOrderStatus.CREATED) {
                throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_READY", "Checkout has not started");
            }
            if (order.status() == PaymentOrderStatus.CHECKOUT_READY) {
                String gatewayTransactionId;
                if (provider == PaymentProvider.BKASH) {
                    var verified = bkash.verify(order, validationId);
                    gatewayTransactionId = verified.gatewayTransactionId();
                } else {
                    var verified = ssl.verify(order, validationId);
                    gatewayTransactionId = verified.gatewayTransactionId();
                }
                try {
                    order = orders.save(order.verified(validationId,
                            gatewayTransactionId, Instant.now()));
                } catch (OptimisticLockingFailureException | DuplicateKeyException exception) {
                    continue;
                }
            } else if (!validationId.equals(order.gatewayValidationId())) {
                throw invalidCallback();
            }
            applyVerifiedOrder(order);
            try {
                return PaymentOrderView.from(orders.save(order.applied(Instant.now())));
            } catch (OptimisticLockingFailureException exception) {
                // A simultaneous IPN/browser callback may already have finished this order.
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_RETRY", "Payment is being processed; retry shortly");
    }

    private void applyVerifiedOrder(PaymentOrder order) {
        if (order.status() != PaymentOrderStatus.VERIFIED) throw invalidCallback();
        for (int attempt = 0; attempt < WRITE_RETRIES; attempt++) {
            Subscription current = subscriptions.findById(order.organizationId()).orElse(null);
            if (current != null && current.appliedOrderIds() != null
                    && current.appliedOrderIds().contains(order.id())) return;
            List<String> applied = new ArrayList<>(current == null || current.appliedOrderIds() == null
                    ? List.of() : current.appliedOrderIds());
            applied.add(order.id());
            Instant now = Instant.now();
            Instant startsAt = current != null && current.currentPeriodEnd() != null
                    && current.currentPeriodEnd().isAfter(now) ? current.currentPeriodEnd() : now;
            Subscription updated = new Subscription(order.organizationId(), PlanTier.TEAM,
                    SubscriptionStatus.ACTIVE, order.id(), startsAt.plus(DEMO_TERM), now,
                    List.copyOf(applied), current == null ? null : current.revision());
            try {
                subscriptions.save(updated);
                return;
            } catch (OptimisticLockingFailureException | DuplicateKeyException exception) {
                // Re-read to avoid double credit when IPN and browser callback race.
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_RETRY", "Payment is being processed; retry shortly");
    }

    private ApiException invalidCallback() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_CALLBACK", "Payment callback is invalid");
    }
}
