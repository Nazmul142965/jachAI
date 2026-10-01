package com.jachai.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
final class LocalEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LocalEmailSender.class);

    @Override
    public void sendVerification(String recipient, String displayName, String verificationUrl) {
        // Development only: use the link from the local console to finish registration.
        log.info("Development verification email for {}: {}", recipient, verificationUrl);
    }

    @Override
    public void sendPasswordReset(String recipient, String displayName, String resetUrl) {
        // Development only: production uses SMTP and never logs reset links.
        log.info("Development password reset email for {}: {}", recipient, resetUrl);
    }
}
