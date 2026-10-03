package com.jachai.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ManualPlanRequest(@NotNull Instant expiresAt,
                                @NotBlank @Size(min = 10, max = 500) String reason) { }
