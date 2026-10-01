package com.jachai.attempt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateAssignmentRequest(
        @NotBlank String userId,
        @Min(1) @Max(5) int attemptsAllowed,
        @NotNull Instant availableFrom,
        @NotNull Instant availableUntil) {
}
