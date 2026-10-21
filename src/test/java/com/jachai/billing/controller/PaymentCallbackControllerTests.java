package com.jachai.billing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.service.PaymentBillingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentCallbackControllerTests {
    private final PaymentBillingService billing = mock(PaymentBillingService.class);

    @Test
    void bkashCancellationReturnsToBillingWithoutVerifyingPayment() {
        var response = new BkashCallbackController(billing).get("order-1", null, "cancel");

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals("/?billing=canceled", response.getHeaders().getLocation().toString());
        verify(billing).cancel("order-1", PaymentProvider.BKASH);
    }

    @Test
    void sslCommerzCancellationReturnsToBillingWithoutVerifyingPayment() {
        var response = new SslCommerzCallbackController(billing).browserPost("order-1", null, "canceled");

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals("/?billing=canceled", response.getHeaders().getLocation().toString());
        verify(billing).cancel("order-1", PaymentProvider.SSLCOMMERZ);
    }
}
