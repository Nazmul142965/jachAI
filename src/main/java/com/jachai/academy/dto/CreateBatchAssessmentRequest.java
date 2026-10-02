package com.jachai.academy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateBatchAssessmentRequest(@NotBlank String assessmentId,
                                           @Min(1) @Max(5) int attemptsAllowed,
                                           @NotNull Instant availableFrom,
                                           @NotNull Instant availableUntil) { }
