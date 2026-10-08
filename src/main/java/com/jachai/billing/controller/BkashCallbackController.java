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
@RequestMapping("/api/v1/billing/bkash/callback")
public class BkashCallbackController {
    private final PaymentBillingService billing;

    @GetMapping
    public ResponseEntity<Void> get(@RequestParam String orderId,
                                    @RequestParam(required = false) String paymentID,
                                    @RequestParam(required = false) String status) {
        return complete(orderId, paymentID, status);
    }

    @PostMapping
    public ResponseEntity<Void> post(@RequestParam String orderId,
                                     @RequestParam(required = false) String paymentID,
                                     @RequestParam(required = false) String status) {
        return complete(orderId, paymentID, status);
    }

    private ResponseEntity<Void> complete(String orderId, String paymentId, String status) {
        String result;
        if ("success".equals(status)) {
            billing.verifyBkashCallback(orderId, paymentId);
            result = "verified";
        } else if ("failure".equals(status) || "cancel".equals(status)) {
            billing.cancel(orderId, PaymentProvider.BKASH);
            result = "failure".equals(status) ? "failed" : "canceled";
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_CALLBACK",
                    "Payment callback is invalid");
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/?billing=" + result)).build();
    }
}
