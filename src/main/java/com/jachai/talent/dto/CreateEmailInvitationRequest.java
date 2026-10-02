package com.jachai.talent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateEmailInvitationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @Min(1) @Max(5) int attemptsAllowed,
        @NotNull Instant availableFrom,
        @NotNull Instant availableUntil) { }
