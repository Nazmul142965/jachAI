package com.jachai.billing.controller;

import static org.mockito.Mockito.mock;

import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.service.PaymentBillingService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class PaymentBillingControllerTests {
    private final PaymentBillingService billing = mock(PaymentBillingService.class);
    private final RateLimitService limits = mock(RateLimitService.class);
    private final PaymentBillingController controller = new PaymentBillingController(billing, limits);
    private final Jwt actor = Jwt.withTokenValue("test-token")
            .header("alg", "none").subject("actor-1").build();

    @Test
    void checkoutConsumesActorLimitBeforeStartingGatewaySession() {
        controller.start(actor, "organization-1",
                new PaymentBillingController.StartOrderRequest(PaymentProvider.BKASH));

        var order = org.mockito.Mockito.inOrder(limits, billing);
        order.verify(limits).consume(RateLimitPolicy.PAYMENT_CHECKOUT_ACTOR, "actor-1");
        order.verify(billing).start("actor-1", "organization-1", PaymentProvider.BKASH);
    }

    @Test
    void reconciliationConsumesActorLimitBeforeProviderLookup() {
        controller.reconcile(actor, "organization-1", "order-1");

        var order = org.mockito.Mockito.inOrder(limits, billing);
        order.verify(limits).consume(RateLimitPolicy.PAYMENT_RECONCILE_ACTOR, "actor-1");
        order.verify(billing).reconcile("actor-1", "organization-1", "order-1");
    }
}
