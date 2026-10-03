package com.jachai.platform.controller;

import com.jachai.platform.dto.PlatformOrganizationView;
import com.jachai.platform.dto.PlatformBillingView;
import com.jachai.billing.dto.PaymentOrderView;
import com.jachai.platform.dto.ChangePlatformRoleRequest;
import com.jachai.platform.dto.PlatformRoleAuditView;
import com.jachai.platform.dto.PlatformRoleView;
import com.jachai.platform.dto.PlatformUserView;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.platform.service.PlatformBillingSupportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {
    private final PlatformAdminService platform;
    private final PlatformBillingSupportService billingSupport;

    public PlatformAdminController(PlatformAdminService platform, PlatformBillingSupportService billingSupport) {
        this.platform = platform;
        this.billingSupport = billingSupport;
    }

    @GetMapping("/me")
    public PlatformAccessView me(@AuthenticationPrincipal Jwt jwt) {
        return new PlatformAccessView(jwt.getSubject(), platform.isPlatformAdmin(jwt.getSubject()));
    }

    @GetMapping("/organizations")
    public Page<PlatformOrganizationView> organizations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return platform.organizations(jwt.getSubject(), page);
    }

    @GetMapping("/organizations/{organizationId}/billing")
    public PlatformBillingView billing(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId) {
        return billingSupport.billing(jwt.getSubject(), organizationId);
    }

    @GetMapping("/organizations/{organizationId}/billing/orders")
    public Page<PaymentOrderView> billingOrders(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return billingSupport.orders(jwt.getSubject(), organizationId, page);
    }

    @GetMapping("/users/{userId}")
    public PlatformUserView user(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        return platform.user(jwt.getSubject(), userId);
    }

    @GetMapping("/users/{userId}/role")
    public PlatformRoleView role(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        return platform.role(jwt.getSubject(), userId);
    }

    @PutMapping("/users/{userId}/role")
    public PlatformRoleView changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId,
            @Valid @RequestBody ChangePlatformRoleRequest request) {
        return platform.changeRole(jwt.getSubject(), userId, request.platformAdmin());
    }

    @GetMapping("/users/{userId}/role/audit")
    public Page<PlatformRoleAuditView> roleHistory(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return platform.roleHistory(jwt.getSubject(), userId, page);
    }

    public record PlatformAccessView(String userId, boolean platformAdmin) { }
}
