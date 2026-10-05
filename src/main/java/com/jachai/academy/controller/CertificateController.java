package com.jachai.academy.controller;

import com.jachai.academy.dto.CertificateView;
import com.jachai.academy.dto.CertificateAuditView;
import com.jachai.academy.dto.CertificateDisplayView;
import com.jachai.academy.service.CertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}")
public class CertificateController {
    private final CertificateService certificates;

    @GetMapping("/certificates/mine")
    public Page<CertificateView> mine(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return certificates.mine(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "issuedAt", "id")));
    }

    @GetMapping("/certificates/{certificateId}/display")
    public CertificateDisplayView display(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String certificateId) {
        return certificates.display(jwt.getSubject(), organizationId, certificateId);
    }

    @PostMapping("/attempts/{attemptId}/certificate")
    public ResponseEntity<CertificateView> issue(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(certificates.issue(jwt.getSubject(), organizationId, attemptId));
    }

    @PostMapping("/certificates/{certificateId}/revoke")
    public CertificateView revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String certificateId) {
        return certificates.revoke(jwt.getSubject(), organizationId, certificateId);
    }

    @GetMapping("/certificates/{certificateId}/audit")
    public Page<CertificateAuditView> audit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String certificateId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return certificates.audit(jwt.getSubject(), organizationId, certificateId, page);
    }
}
