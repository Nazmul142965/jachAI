package com.jachai.academy.controller;

import com.jachai.academy.dto.CertificateView;
import com.jachai.academy.service.CertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}")
public class CertificateController {
    private final CertificateService certificates;

    @GetMapping("/certificates/mine")
    public List<CertificateView> mine(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId) {
        return certificates.mine(jwt.getSubject(), organizationId);
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
}
