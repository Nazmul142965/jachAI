package com.jachai.auth.service;

public interface EmailSender {
    void sendVerification(String recipient, String displayName, String verificationUrl);
    void sendPasswordReset(String recipient, String displayName, String resetUrl);
}
