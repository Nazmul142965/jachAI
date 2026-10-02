package com.jachai.auth.controller;

import com.jachai.auth.dto.AuthResponse;
import com.jachai.auth.dto.LoginRequest;
import com.jachai.auth.dto.RegisterRequest;
import com.jachai.auth.dto.VerifyEmailRequest;
import com.jachai.auth.service.AuthService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final RateLimitService limits;

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request,
                                                         HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.REGISTER_IP, clientIp(servlet));
        limits.consume(RateLimitPolicy.REGISTER_ACCOUNT, emailKey(request.email()));
        auth.register(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "Check your email to verify your account"));
    }

    @PostMapping("/verify-email")
    public Map<String, String> verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                            HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.VERIFY_IP, clientIp(servlet));
        auth.verifyEmail(request.token());
        return Map.of("message", "Email verified");
    }

    public record ResendRequest(@NotBlank @Email String email) { }
    public record RefreshRequest(@NotBlank String refreshToken) { }
    public record RequestResetRequest(@NotBlank @Email String email) { }
    public record ResetPasswordRequest(@NotBlank String token,
                                       @NotBlank @Size(min = 12, max = 128) String newPassword) { }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resend(@Valid @RequestBody ResendRequest request,
                                                       HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.RESEND_IP, clientIp(servlet));
        limits.consume(RateLimitPolicy.RESEND_ACCOUNT, emailKey(request.email()));
        auth.resendVerification(request.email());
        return ResponseEntity.accepted().body(Map.of("message", "If the account needs verification, an email has been sent"));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.LOGIN_IP, clientIp(servlet));
        limits.consume(RateLimitPolicy.LOGIN_ACCOUNT, emailKey(request.email()));
        return auth.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.REFRESH_IP, clientIp(servlet));
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/request-password-reset")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody RequestResetRequest request, HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.RESET_IP, clientIp(servlet));
        limits.consume(RateLimitPolicy.RESET_ACCOUNT, emailKey(request.email()));
        auth.requestPasswordReset(request.email());
        return ResponseEntity.accepted().body(Map.of("message",
                "If the account is eligible, a reset email has been sent"));
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest servlet) {
        limits.consume(RateLimitPolicy.VERIFY_IP, clientIp(servlet));
        auth.resetPassword(request.token(), request.newPassword());
        return Map.of("message", "Password reset. Sign in with your new password");
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody RefreshRequest request) {
        auth.logout(jwt.getSubject(), request.refreshToken(), jwt.getId(), jwt.getExpiresAt());
        return Map.of("message", "Access token and refresh session revoked");
    }

    private static String clientIp(HttpServletRequest servlet) {
        // Do not trust user-controlled X-Forwarded-For without a configured trusted proxy.
        String address = servlet.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    private static String emailKey(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
