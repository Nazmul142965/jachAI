package com.jachai.auth.controller;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.dto.LoginRequest;
import com.jachai.auth.dto.RegisterRequest;
import com.jachai.auth.dto.VerifyEmailRequest;
import com.jachai.auth.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        auth.register(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "Check your email to verify your account"));
    }

    @PostMapping("/verify-email")
    public Map<String, String> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        auth.verifyEmail(request.token());
        return Map.of("message", "Email verified");
    }

    public record ResendRequest(@NotBlank @Email String email) { }
    public record RefreshRequest(@NotBlank String refreshToken) { }
    public record RequestResetRequest(@NotBlank @Email String email) { }
    public record ResetPasswordRequest(@NotBlank String token,
                                       @NotBlank @Size(min = 12, max = 128) String newPassword) { }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resend(@Valid @RequestBody ResendRequest request) {
        auth.resendVerification(request.email());
        return ResponseEntity.accepted().body(Map.of("message", "If the account needs verification, an email has been sent"));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/request-password-reset")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody RequestResetRequest request) {
        auth.requestPasswordReset(request.email());
        return ResponseEntity.accepted().body(Map.of("message",
                "If the account is eligible, a reset email has been sent"));
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        auth.resetPassword(request.token(), request.newPassword());
        return Map.of("message", "Password reset. Sign in with your new password");
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
        return Map.of("message", "Refresh session revoked");
    }
}
