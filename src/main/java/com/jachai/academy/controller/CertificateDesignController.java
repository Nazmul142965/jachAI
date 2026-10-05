package com.jachai.academy.controller;

import com.jachai.academy.dto.CertificateDesignView;
import com.jachai.academy.dto.UpdateCertificateDesignRequest;
import com.jachai.academy.service.CertificateDesignService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/certificate-design")
public class CertificateDesignController {
    private final CertificateDesignService designs;

    @GetMapping
    public CertificateDesignView current(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable String organizationId) {
        return designs.current(jwt.getSubject(), organizationId);
    }

    @PutMapping
    public CertificateDesignView update(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable String organizationId,
                                        @Valid @RequestBody UpdateCertificateDesignRequest request) {
        return designs.update(jwt.getSubject(), organizationId, request);
    }
}
