package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
