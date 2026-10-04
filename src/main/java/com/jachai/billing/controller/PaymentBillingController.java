package com.jachai.billing.controller;

import com.jachai.billing.dto.CheckoutView;
import com.jachai.billing.dto.PaymentOrderView;
import com.jachai.billing.dto.PaymentOptionsView;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.service.PaymentBillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/billing")
public class PaymentBillingController {
    private final PaymentBillingService billing;

    public record StartOrderRequest(@NotNull PaymentProvider provider) { }

    @GetMapping("/options")
    public PaymentOptionsView options(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable String organizationId) {
        return billing.options(jwt.getSubject(), organizationId);
    }

    @PostMapping("/orders")
    public ResponseEntity<CheckoutView> start(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable String organizationId,
                                              @Valid @RequestBody StartOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(billing.start(jwt.getSubject(), organizationId, request.provider()));
    }

    @GetMapping("/orders")
    public Page<PaymentOrderView> history(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable String organizationId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return billing.history(jwt.getSubject(), organizationId, page);
    }

    @GetMapping("/orders/{orderId}")
    public PaymentOrderView order(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable String organizationId,
                                  @PathVariable String orderId) {
        return billing.order(jwt.getSubject(), organizationId, orderId);
    }

    @PostMapping("/orders/{orderId}/reconcile")
    public PaymentOrderView reconcile(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable String organizationId,
                                      @PathVariable String orderId) {
        return billing.reconcile(jwt.getSubject(), organizationId, orderId);
    }
}
