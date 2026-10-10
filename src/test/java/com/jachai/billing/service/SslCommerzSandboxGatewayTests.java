package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SslCommerzSandboxGatewayTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsOnlyMatchingSandboxValidation() throws Exception {
        var response = json.readTree("""
                {"status":"VALID", "tran_id":"J123456789012345678901234", "val_id":"validation1",
                 "currency":"BDT", "currency_type":"BDT", "currency_amount":"100.00",
                 "risk_level":0, "bank_tran_id":"bank123", "store_id":"sandbox-store"}
                """);

        var paid = SslCommerzSandboxGateway.verifiedResponse(order(), "validation1",
                response, "sandbox-store");

        assertEquals("bank123", paid.gatewayTransactionId());
    }

    @Test
    void rejectsMismatchedAmountOrTransactionAndRiskyPayment() throws Exception {
        String base = """
                {"status":"VALID", "tran_id":"J123456789012345678901234", "val_id":"validation1",
                 "currency":"BDT", "currency_type":"BDT", "currency_amount":"100.00",
                 "risk_level":0, "bank_tran_id":"bank123", "store_id":"sandbox-store"}
                """;
        assertEquals("PAYMENT_AMOUNT_MISMATCH", assertThrows(ApiException.class,
                () -> SslCommerzSandboxGateway.verifiedResponse(order(), "validation1",
                        json.readTree(base.replace("100.00", "99.00")), "sandbox-store")).code());
        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> SslCommerzSandboxGateway.verifiedResponse(order(), "validation1",
                        json.readTree(base.replace("J123456789012345678901234", "other")),
                        "sandbox-store")).code());
        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> SslCommerzSandboxGateway.verifiedResponse(order(), "validation1",
                        json.readTree(base.replace("\"risk_level\":0", "\"risk_level\":1")),
                        "sandbox-store")).code());
        assertEquals("PAYMENT_NOT_VERIFIED", assertThrows(ApiException.class,
                () -> SslCommerzSandboxGateway.verifiedResponse(order(), "validation1",
                        json.readTree(base), "different-store")).code());
    }

    @Test
    void querySelectsOnlySuccessfulMatchingValidationReference() throws Exception {
        var response = json.readTree("""
                {"APIConnect":"DONE","no_of_trans_found":3,"element":[
                  {"status":"FAILED","tran_id":"J123456789012345678901234","val_id":"failed"},
                  {"status":"VALID","tran_id":"another-order","val_id":"foreign"},
                  {"status":"VALIDATED","tran_id":"J123456789012345678901234","val_id":"validation1"}
                ]}
                """);

        assertEquals("validation1", SslCommerzSandboxGateway.validationIdFromQuery(order(), response)
                .orElseThrow());
    }

    @Test
    void queryDoesNotTurnPendingOrMalformedResultsIntoPaymentProof() throws Exception {
        assertTrue(SslCommerzSandboxGateway.validationIdFromQuery(order(), json.readTree("""
                {"APIConnect":"DONE","element":[
                  {"status":"PENDING","tran_id":"J123456789012345678901234","val_id":"pending"},
                  {"status":"VALID","tran_id":"J123456789012345678901234","val_id":"bad space"}
                ]}
                """)).isEmpty());
        assertEquals("BILLING_PROVIDER_ERROR", assertThrows(ApiException.class,
                () -> SslCommerzSandboxGateway.validationIdFromQuery(order(),
                        json.readTree("{\"APIConnect\":\"FAILED\",\"element\":[]}"))).code());
    }

    @Test
    void queryRejectsMultipleSuccessfulValidationReferencesForOneOrder() throws Exception {
        var response = json.readTree("""
                {"APIConnect":"DONE","element":[
                  {"status":"VALID","tran_id":"J123456789012345678901234","val_id":"validation1"},
                  {"status":"VALIDATED","tran_id":"J123456789012345678901234","val_id":"validation2"}
                ]}
                """);

        assertEquals("PAYMENT_AMBIGUOUS", assertThrows(ApiException.class,
                () -> SslCommerzSandboxGateway.validationIdFromQuery(order(), response)).code());
    }

    private PaymentOrder order() {
        return new PaymentOrder("J123456789012345678901234", "org", "admin",
                PaymentProvider.SSLCOMMERZ, PaymentOrderStatus.CHECKOUT_READY,
                new BigDecimal("100.00"), "session", null, null,
                Instant.now(), null, null, 1L);
    }
}
