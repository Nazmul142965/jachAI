package com.jachai.academy.controller;

import com.jachai.academy.dto.CertificateVerificationView;
import com.jachai.academy.service.CertificateService;
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

    @GetMapping("/{verificationCode}")
    public CertificateVerificationView verify(@PathVariable String verificationCode) {
        return certificates.verify(verificationCode);
    }
}
