package com.jachai.mail.service;

import com.jachai.auth.service.EmailSender;
import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.mail.repository.EmailJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueuedEmailSender implements EmailSender {
    private static final Duration RECORD_RETENTION = Duration.ofDays(30);
    private final EmailJobRepository jobs;
    private final EmailPayloadCipher cipher;

    @Override
    public void sendVerification(String recipient, String displayName, String verificationUrl) {
        enqueue(new EmailPayload(EmailJobType.VERIFICATION, recipient, displayName, null,
                verificationUrl, Instant.now().plus(Duration.ofHours(24))));
    }

    @Override
    public void sendPasswordReset(String recipient, String displayName, String resetUrl) {
        enqueue(new EmailPayload(EmailJobType.PASSWORD_RESET, recipient, displayName, null,
                resetUrl, Instant.now().plus(Duration.ofMinutes(30))));
    }

    @Override
    public void sendAssessmentInvitation(String recipient, String organizationName,
                                         String campaignTitle, String invitationUrl, Instant expiresAt) {
        enqueue(new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                recipient, organizationName, campaignTitle, invitationUrl, expiresAt));
    }

    @Override
    public void sendOrganizationInvitation(String recipient, String organizationName,
                                           String role, String invitationUrl, Instant expiresAt) {
        enqueue(new EmailPayload(EmailJobType.ORGANIZATION_INVITATION,
                recipient, organizationName, role, invitationUrl, expiresAt));
    }

    @Override
    public void sendCertificateIssued(String certificateId, String recipient, String displayName,
                                      String verificationUrl) {
        EmailPayload payload = new EmailPayload(EmailJobType.CERTIFICATE_ISSUED,
                recipient, displayName, null, verificationUrl,
                Instant.now().plus(Duration.ofDays(7)), certificateId);
        try {
            enqueue("certificate-issued:" + certificateId, payload, null);
        } catch (DuplicateKeyException ignored) {
            // The durable job ID makes retries and concurrent issuance idempotent.
        }
    }

    @Override
    public void sendResultReleased(String attemptId, String recipient, String displayName, String signInUrl) {
        EmailPayload payload = new EmailPayload(EmailJobType.RESULT_RELEASED,
                recipient, displayName, null, signInUrl,
                Instant.now().plus(Duration.ofDays(7)), attemptId);
        try {
            enqueue("result-released:" + attemptId, payload, null);
        } catch (DuplicateKeyException ignored) {
            // A released attempt has only one notification, even after retries.
        }
    }

    @Override
    public void sendFeedbackPublished(String feedbackId, String recipient, String displayName, String signInUrl) {
        EmailPayload payload = new EmailPayload(EmailJobType.FEEDBACK_PUBLISHED,
                recipient, displayName, null, signInUrl,
                Instant.now().plus(Duration.ofDays(7)), feedbackId);
        try {
            enqueue("feedback-published:" + feedbackId, payload, null);
        } catch (DuplicateKeyException ignored) {
            // Retry can repair a failed enqueue without delivering a second notification.
        }
    }

    @Override
    public boolean sendAssignmentReminder(String assignmentId, String recipient, String displayName,
                                          String assessmentTitle, String signInUrl, Instant availableUntil) {
        Instant now = Instant.now();
        Instant validUntil = availableUntil.isBefore(now.plus(Duration.ofDays(7)))
                ? availableUntil : now.plus(Duration.ofDays(7));
        EmailPayload payload = new EmailPayload(EmailJobType.ASSIGNMENT_REMINDER,
                recipient, displayName, assessmentTitle, signInUrl, validUntil, assignmentId);
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        try {
            enqueue("assignment-reminder:" + assignmentId + ":" + day, payload,
                    now.plus(RECORD_RETENTION));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public void sendBillingNotice(String orderId, String recipient, String displayName,
                                  String orderSummary, String signInUrl) {
        EmailPayload payload = new EmailPayload(EmailJobType.BILLING_NOTICE,
                recipient, displayName, orderSummary, signInUrl,
                Instant.now().plus(Duration.ofDays(30)), orderId);
        try {
            enqueue("billing-notice:" + orderId, payload, null);
        } catch (DuplicateKeyException ignored) {
            // One notice per verified-and-applied sandbox order, even after reconciliation.
        }
    }

    @Override
    public void sendSupportTicketNotification(String ticketId, String referenceNumber,
                                              String recipient, String displayName,
                                              String subject, String signInUrl, Instant expiresAt) {
        EmailPayload payload = new EmailPayload(EmailJobType.SUPPORT_TICKET_UPDATE,
                recipient, displayName, subject, signInUrl, expiresAt, ticketId);
        try {
            enqueue("support-ticket:" + ticketId + ":" + referenceNumber, payload, expiresAt);
        } catch (DuplicateKeyException ignored) {
            // Idempotent
        }
    }

    private void enqueue(EmailPayload payload) {
        enqueue(UUID.randomUUID().toString(), payload, Instant.now().plus(RECORD_RETENTION));
    }

    private void enqueue(String id, EmailPayload payload, Instant expiresAt) {
        Instant now = Instant.now();
        EmailPayloadCipher.Sealed sealed = cipher.seal(id, payload);
        jobs.insert(new EmailJob(id, sealed.encryptedPayload(), sealed.nonce(),
                EmailJobStatus.PENDING, 0, now, null, now, null,
                expiresAt));
    }
}