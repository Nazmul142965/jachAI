package com.jachai.platform.controller;

import com.jachai.platform.dto.PlatformOrganizationView;
import com.jachai.platform.dto.PlatformUserView;
import com.jachai.platform.service.PlatformAdminService;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {
    private final PlatformAdminService platform;

    public PlatformAdminController(PlatformAdminService platform) {
        this.platform = platform;
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

    @GetMapping("/users/{userId}")
    public PlatformUserView user(@AuthenticationPrincipal Jwt jwt, @PathVariable String userId) {
        return platform.user(jwt.getSubject(), userId);
    }

    public record PlatformAccessView(String userId, boolean platformAdmin) { }
}
