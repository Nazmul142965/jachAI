package com.jachai.auth.service;

import java.time.Instant;

public interface EmailSender {
    void sendVerification(String recipient, String displayName, String verificationUrl);
    void sendPasswordReset(String recipient, String displayName, String resetUrl);
    void sendAssessmentInvitation(String recipient, String organizationName,
                                  String campaignTitle, String invitationUrl, Instant expiresAt);
    void sendCertificateIssued(String certificateId, String recipient, String displayName,
                               String verificationUrl);
}
