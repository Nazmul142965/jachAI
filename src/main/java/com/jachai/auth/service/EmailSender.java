package com.jachai.auth.service;

import java.time.Instant;

public interface EmailSender {
    void sendVerification(String recipient, String displayName, String verificationUrl);
    void sendPasswordReset(String recipient, String displayName, String resetUrl);
    void sendAssessmentInvitation(String recipient, String organizationName,
                                  String campaignTitle, String invitationUrl, Instant expiresAt);
    void sendOrganizationInvitation(String recipient, String organizationName,
                                    String role, String invitationUrl, Instant expiresAt);
    void sendCertificateIssued(String certificateId, String recipient, String displayName,
                               String verificationUrl);
    void sendResultReleased(String attemptId, String recipient, String displayName, String signInUrl);
    void sendFeedbackPublished(String feedbackId, String recipient, String displayName, String signInUrl);
    boolean sendAssignmentReminder(String assignmentId, String recipient, String displayName,
                                   String assessmentTitle, String signInUrl, Instant availableUntil);
    void sendBillingNotice(String orderId, String recipient, String displayName,
                           String orderSummary, String signInUrl);
}
