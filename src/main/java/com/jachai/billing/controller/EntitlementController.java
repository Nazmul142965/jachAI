package com.jachai.billing.controller;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.dto.UsageReconciliationReport;
import com.jachai.billing.service.EntitlementService;
import com.jachai.billing.service.EntitlementUsageReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/billing")
public class EntitlementController {
    private final EntitlementService entitlements;
    private final EntitlementUsageReconciliationService reconciliation;

    @GetMapping("/entitlements")
    public PlanEntitlements current(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable String organizationId) {
        return entitlements.forAdmin(jwt.getSubject(), organizationId);
    }

    @GetMapping("/entitlements/usage-reconciliation")
    public UsageReconciliationReport report(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String organizationId) {
        return reconciliation.report(jwt.getSubject(), organizationId);
    }

    @PostMapping("/entitlements/usage-reconciliation")
    public UsageReconciliationReport repair(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable String organizationId) {
        return reconciliation.repair(jwt.getSubject(), organizationId);
    }
}
