package com.jachai.platform.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssessmentSupportPreviewRequest(
        @NotBlank @Size(max = 100) String assessmentId,
        @NotBlank @Size(min = 6, max = 80) String ticketReference,
        @NotBlank @Size(min = 20, max = 500) String reason,
        @AssertTrue boolean acknowledgeTenantContent) { }
