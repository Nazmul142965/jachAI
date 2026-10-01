package com.jachai.auth.service;

public interface EmailSender {
    void sendVerification(String recipient, String displayName, String verificationUrl);
}
