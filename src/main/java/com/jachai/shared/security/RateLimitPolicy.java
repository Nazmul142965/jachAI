package com.jachai.shared.security;

import java.time.Duration;

public enum RateLimitPolicy {
    LOGIN_IP(10, Duration.ofMinutes(1)),
    LOGIN_ACCOUNT(20, Duration.ofMinutes(15)),
    REGISTER_IP(5, Duration.ofHours(1)),
    REGISTER_ACCOUNT(3, Duration.ofHours(24)),
    RESEND_IP(5, Duration.ofHours(1)),
    RESEND_ACCOUNT(3, Duration.ofHours(1)),
    RESET_IP(5, Duration.ofHours(1)),
    RESET_ACCOUNT(3, Duration.ofHours(1)),
    PASSWORD_CHANGE_ACCOUNT(5, Duration.ofHours(1)),
    VERIFY_IP(30, Duration.ofMinutes(1)),
    REFRESH_IP(60, Duration.ofMinutes(1)),
    CERTIFICATE_IP(60, Duration.ofMinutes(1)),
    INVITE_ACTOR(30, Duration.ofHours(1)),
    JOIN_CODE_ACCOUNT(10, Duration.ofHours(1)),
    PLATFORM_SUPPORT(5, Duration.ofHours(1)),
    SUPPORT_TICKET_CREATE(10, Duration.ofHours(1)),
    PAYMENT_CHECKOUT_ACTOR(5, Duration.ofHours(1)),
    PAYMENT_RECONCILE_ACTOR(30, Duration.ofMinutes(1)),
    AI_ACTOR(10, Duration.ofMinutes(1)),
    PRACTICE_SESSION_ACTOR(30, Duration.ofHours(1)),
    RESUME_SCAN_ACTOR(10, Duration.ofHours(1)),
    RESUME_REVIEW_ACCOUNT(5, Duration.ofHours(24)),
    RESUME_REVIEW_PLATFORM(100, Duration.ofHours(24)),
    MESSAGE_ACTOR(30, Duration.ofMinutes(1));

    private final long limit;
    private final Duration window;

    RateLimitPolicy(long limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }

    public long limit() { return limit; }
    public Duration window() { return window; }
}
