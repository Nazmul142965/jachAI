package com.jachai.billing.controller;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/billing")
public class EntitlementController {
    private final EntitlementService entitlements;

    @GetMapping("/entitlements")
    public PlanEntitlements current(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable String organizationId) {
        return entitlements.forAdmin(jwt.getSubject(), organizationId);
    }
}
