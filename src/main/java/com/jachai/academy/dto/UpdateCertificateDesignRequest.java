package com.jachai.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCertificateDesignRequest(
        @NotBlank @Size(max = 100) String heading,
        @NotBlank @Size(max = 500) String message,
        @Size(max = 100) String signatoryName,
        @Size(max = 100000) String logoDataUrl,
        @Size(max = 100000) String signatureDataUrl) { }
