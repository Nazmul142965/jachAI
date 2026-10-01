package com.jachai.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 2, max = 80) String displayName,
        @NotBlank @Size(min = 12, max = 128) String password) {
}
