package com.jachai.academy.controller;

import com.jachai.academy.dto.CertificateVerificationView;
import com.jachai.academy.service.CertificateService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import com.jachai.shared.security.TrustedClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/certificates")
public class PublicCertificateController {
    private final CertificateService certificates;
    private final RateLimitService limits;
    private final TrustedClientIpResolver clientIps;

    @GetMapping("/{verificationCode}")
    public CertificateVerificationView verify(@PathVariable String verificationCode,
                                               HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.CERTIFICATE_IP, clientIps.resolve(servlet));
        return certificates.verify(verificationCode);
    }
}
