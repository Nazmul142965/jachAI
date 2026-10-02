package com.jachai.mail.service;

import com.jachai.auth.service.EmailSender;
import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.mail.repository.EmailJobRepository;
import java.time.Duration;
import java.time.Instant;
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
