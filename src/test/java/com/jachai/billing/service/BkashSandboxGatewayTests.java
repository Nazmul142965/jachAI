package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BkashSandboxGatewayTests {
    private static final String ORDER_ID = "J123456789012345678901234";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsOnlyCompletedMatchingPayment() throws Exception {
        var response = json.readTree(valid());
        assertEquals("trx123", BkashSandboxGateway.verifiedResponse(order(), "payment12345",
                response).gatewayTransactionId());
    }

    @Test
    void rejectsWrongInvoiceAmountOrStatus() throws Exception {
        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> BkashSandboxGateway.verifiedResponse(order(), "payment12345",
                        json.readTree(valid().replace(ORDER_ID, "other")))).code());
        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> BkashSandboxGateway.verifiedResponse(order(), "payment12345",
                        json.readTree(valid().replace("100.00", "99.00")))).code());
        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> BkashSandboxGateway.verifiedResponse(order(), "payment12345",
                        json.readTree(valid().replace("Completed", "Initiated")))).code());
    }

    private String valid() {
        return """
                {"statusCode":"0000","transactionStatus":"Completed",
                 "paymentID":"payment12345","merchantInvoiceNumber":"J123456789012345678901234",
                 "currency":"BDT","intent":"sale","trxID":"trx123","amount":"100.00"}
                """;
    }

    private PaymentOrder order() {
        return new PaymentOrder(ORDER_ID, "org", "admin", PaymentProvider.BKASH,
                PaymentOrderStatus.CHECKOUT_READY, new BigDecimal("100.00"), "payment12345",
                null, null, Instant.now(), null, null, 1L);
    }
}
