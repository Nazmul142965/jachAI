package com.jachai.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.auth.controller.AuthController;
import com.jachai.auth.dto.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class AuthPasswordValidationTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void registrationAcceptsSixCharactersButRejectsFive() {
        assertTrue(validator.validate(new RegisterRequest("user@example.com", "Test User", "six123")).isEmpty());
        assertFalse(validator.validate(new RegisterRequest("user@example.com", "Test User", "five1")).isEmpty());
    }

    @Test
    void passwordResetUsesTheSameMinimum() {
        assertTrue(validator.validate(new AuthController.ResetPasswordRequest("token", "six123")).isEmpty());
        assertFalse(validator.validate(new AuthController.ResetPasswordRequest("token", "five1")).isEmpty());
    }
}
