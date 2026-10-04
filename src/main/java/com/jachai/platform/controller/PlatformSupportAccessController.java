package com.jachai.platform.controller;

import com.jachai.platform.dto.AssessmentSupportPreview;
import com.jachai.platform.dto.AssessmentSupportPreviewRequest;
import com.jachai.platform.dto.SupportAccessAuditView;
import com.jachai.platform.service.PlatformSupportAccessService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/platform/organizations/{organizationId}/support/assessment-access")
public class PlatformSupportAccessController {
    private final PlatformSupportAccessService support;
    private final RateLimitService limits;

    @PostMapping
    public AssessmentSupportPreview preview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody AssessmentSupportPreviewRequest request) {
        limits.consume(RateLimitPolicy.PLATFORM_SUPPORT, jwt.getSubject());
        return support.preview(jwt.getSubject(), organizationId, request);
    }

    @GetMapping("/audit")
    public Page<SupportAccessAuditView> history(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return support.platformHistory(jwt.getSubject(), organizationId, page);
    }
}
