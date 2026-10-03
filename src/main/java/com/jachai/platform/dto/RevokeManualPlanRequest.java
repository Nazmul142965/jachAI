package com.jachai.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevokeManualPlanRequest(@NotBlank @Size(min = 10, max = 500) String reason) { }
