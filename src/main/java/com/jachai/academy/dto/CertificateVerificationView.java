package com.jachai.academy.dto;

import java.time.Instant;

public record CertificateVerificationView(String verificationCode, boolean valid,
                                          String organizationName, String assessmentTitle,
                                          Instant issuedAt, Instant revokedAt) { }
