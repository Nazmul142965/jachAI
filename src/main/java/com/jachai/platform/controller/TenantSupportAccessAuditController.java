package com.jachai.platform.controller;

import com.jachai.platform.dto.SupportAccessAuditView;
import com.jachai.platform.service.PlatformSupportAccessService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/support/assessment-access/audit")
public class TenantSupportAccessAuditController {
    private final PlatformSupportAccessService support;

    @GetMapping
    public Page<SupportAccessAuditView> history(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return support.tenantHistory(jwt.getSubject(), organizationId, page);
    }
}
