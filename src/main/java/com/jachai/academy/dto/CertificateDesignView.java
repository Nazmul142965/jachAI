package com.jachai.academy.dto;

import com.jachai.academy.model.CertificateDesign;
import java.time.Instant;

public record CertificateDesignView(String id, String heading, String message,
                                    String signatoryName, String logoDataUrl,
                                    String signatureDataUrl, Instant createdAt) {
    public static CertificateDesignView from(CertificateDesign design) {
        return new CertificateDesignView(design.id(), design.heading(), design.message(),
                design.signatoryName(), design.logoDataUrl(), design.signatureDataUrl(),
                design.createdAt());
    }
}
