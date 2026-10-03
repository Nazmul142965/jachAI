package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.mail.repository.EmailJobRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class QueuedEmailSenderTests {
    @Mock EmailJobRepository jobs;
    @Mock EmailPayloadCipher cipher;

    @Test
    void resetEmailIsStoredAsEncryptedPendingJobWithTokenDeadline() {
        when(cipher.seal(any(), any())).thenReturn(new EmailPayloadCipher.Sealed("ciphertext", "nonce"));
        QueuedEmailSender sender = new QueuedEmailSender(jobs, cipher);
        Instant before = Instant.now();

        sender.sendPasswordReset("learner@example.com", "Learner", "https://example.test/?token=secret");

        ArgumentCaptor<EmailPayload> payload = ArgumentCaptor.forClass(EmailPayload.class);
        verify(cipher).seal(any(), payload.capture());
        assertEquals(EmailJobType.PASSWORD_RESET, payload.getValue().type());
        assertTrue(payload.getValue().validUntil().isAfter(before.plusSeconds(29 * 60)));
        ArgumentCaptor<EmailJob> job = ArgumentCaptor.forClass(EmailJob.class);
        verify(jobs).insert(job.capture());
        assertEquals(EmailJobStatus.PENDING, job.getValue().status());
        assertEquals("ciphertext", job.getValue().encryptedPayload());
        assertEquals(0, job.getValue().attempts());
    }

    @Test
    void organizationInvitationLinkIsQueuedEncryptedWithItsExpiry() {
        when(cipher.seal(any(), any())).thenReturn(new EmailPayloadCipher.Sealed("ciphertext", "nonce"));
        QueuedEmailSender sender = new QueuedEmailSender(jobs, cipher);
        Instant expiry = Instant.now().plusSeconds(3600);

        sender.sendOrganizationInvitation("person@example.com", "Acme", "INSTRUCTOR",
                "https://example.test/?memberInvitation=secret", expiry);

        ArgumentCaptor<EmailPayload> payload = ArgumentCaptor.forClass(EmailPayload.class);
        verify(cipher).seal(any(), payload.capture());
        assertEquals(EmailJobType.ORGANIZATION_INVITATION, payload.getValue().type());
        assertEquals(expiry, payload.getValue().validUntil());
        ArgumentCaptor<EmailJob> job = ArgumentCaptor.forClass(EmailJob.class);
        verify(jobs).insert(job.capture());
        assertEquals("ciphertext", job.getValue().encryptedPayload());
    }

    @Test
    void certificateEmailUsesPermanentDeterministicJobId() {
        when(cipher.seal(any(), any())).thenReturn(new EmailPayloadCipher.Sealed("ciphertext", "nonce"));
        QueuedEmailSender sender = new QueuedEmailSender(jobs, cipher);

        sender.sendCertificateIssued("cert-1", "learner@example.com", "Learner",
                "https://example.test/certificates/code");

        ArgumentCaptor<EmailPayload> payload = ArgumentCaptor.forClass(EmailPayload.class);
        verify(cipher).seal(org.mockito.ArgumentMatchers.eq("certificate-issued:cert-1"), payload.capture());
        assertEquals(EmailJobType.CERTIFICATE_ISSUED, payload.getValue().type());
        assertEquals("cert-1", payload.getValue().resourceId());
        ArgumentCaptor<EmailJob> job = ArgumentCaptor.forClass(EmailJob.class);
        verify(jobs).insert(job.capture());
        assertEquals("certificate-issued:cert-1", job.getValue().id());
        assertEquals(null, job.getValue().expiresAt());
    }

    @Test
    void certificateEmailRetryIgnoresExistingJob() {
        when(cipher.seal(any(), any())).thenReturn(new EmailPayloadCipher.Sealed("ciphertext", "nonce"));
        doThrow(new DuplicateKeyException("already queued")).when(jobs).insert(any(EmailJob.class));

        new QueuedEmailSender(jobs, cipher).sendCertificateIssued("cert-1", "learner@example.com",
                "Learner", "https://example.test/certificates/code");
    }

    @Test
    void resultEmailUsesPermanentDeterministicJobId() {
        when(cipher.seal(any(), any())).thenReturn(new EmailPayloadCipher.Sealed("ciphertext", "nonce"));
        new QueuedEmailSender(jobs, cipher).sendResultReleased("attempt-1", "learner@example.com",
                "Learner", "https://example.test/");

        ArgumentCaptor<EmailPayload> payload = ArgumentCaptor.forClass(EmailPayload.class);
        verify(cipher).seal(org.mockito.ArgumentMatchers.eq("result-released:attempt-1"), payload.capture());
        assertEquals(EmailJobType.RESULT_RELEASED, payload.getValue().type());
        assertEquals("attempt-1", payload.getValue().resourceId());
        ArgumentCaptor<EmailJob> job = ArgumentCaptor.forClass(EmailJob.class);
        verify(jobs).insert(job.capture());
        assertEquals(null, job.getValue().expiresAt());
    }

    @Test
    void repeatedResultEmailIgnoresExistingJob() {
        when(cipher.seal(any(), any())).thenReturn(new EmailPayloadCipher.Sealed("ciphertext", "nonce"));
        doThrow(new DuplicateKeyException("already queued")).when(jobs).insert(any(EmailJob.class));

        new QueuedEmailSender(jobs, cipher).sendResultReleased("attempt-1", "learner@example.com",
                "Learner", "https://example.test/");
    }
}
