package com.jachai.organization.controller;

import com.jachai.organization.dto.OrganizationSettingsView;
import com.jachai.organization.dto.UpdateOrganizationSettingsRequest;
import com.jachai.organization.service.OrganizationSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/settings")
public class OrganizationSettingsController {
    private final OrganizationSettingsService settings;

    @GetMapping
    public OrganizationSettingsView get(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable String organizationId) {
        return settings.get(jwt.getSubject(), organizationId);
    }

    @PutMapping
    public OrganizationSettingsView update(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String organizationId,
                                           @Valid @RequestBody UpdateOrganizationSettingsRequest request) {
        return settings.update(jwt.getSubject(), organizationId, request);
    }
}
