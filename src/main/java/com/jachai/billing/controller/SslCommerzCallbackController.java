package com.jachai.billing.controller;

import com.jachai.billing.service.PaymentBillingService;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.shared.error.ApiException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/billing/sslcommerz")
public class SslCommerzCallbackController {
    private final PaymentBillingService billing;

    @PostMapping("/ipn")
    public ResponseEntity<Void> ipn(@RequestParam(required = false, name = "tran_id") String orderId,
                                    @RequestParam(required = false, name = "val_id") String validationId) {
        if (validationId != null && !validationId.isBlank()) {
            billing.verifySslCallback(orderId, validationId);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> browserGet(@RequestParam(required = false, name = "tran_id") String orderId,
                                           @RequestParam(required = false, name = "val_id") String validationId,
                                           @RequestParam(required = false) String outcome) {
        return browser(orderId, validationId, outcome);
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> browserPost(@RequestParam(required = false, name = "tran_id") String orderId,
                                            @RequestParam(required = false, name = "val_id") String validationId,
                                            @RequestParam(required = false) String outcome) {
        return browser(orderId, validationId, outcome);
    }

    private ResponseEntity<Void> browser(String orderId, String validationId, String outcome) {
        String result;
        if (validationId != null && !validationId.isBlank()) {
            billing.verifySslCallback(orderId, validationId);
            result = "verified";
        } else if ("failed".equals(outcome) || "canceled".equals(outcome)) {
            billing.cancel(orderId, PaymentProvider.SSLCOMMERZ);
            result = outcome;
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_CALLBACK",
                    "Payment callback is invalid");
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/?billing=" + result)).build();
    }
}
