package com.jachai.academy.dto;

import com.jachai.academy.model.CertificateStatus;
import java.time.Instant;

public record CertificateDisplayView(String verificationCode, CertificateStatus status,
                                     String organizationName, String holderName,
                                     String assessmentTitle, Instant issuedAt,
                                     CertificateDesignView design) { }
