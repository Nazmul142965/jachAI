package com.jachai.academy.dto;

import com.jachai.academy.model.Certificate;
import com.jachai.academy.model.CertificateStatus;
import java.time.Instant;

public record CertificateView(String id, String verificationCode, CertificateStatus status,
                              Instant issuedAt, Instant revokedAt) {
    public static CertificateView from(Certificate certificate) {
        return new CertificateView(certificate.id(), certificate.verificationCode(),
                certificate.status(), certificate.issuedAt(), certificate.revokedAt());
    }
}
