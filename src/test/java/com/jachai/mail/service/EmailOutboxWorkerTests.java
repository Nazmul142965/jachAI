package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class EmailOutboxWorkerTests {
    @Mock MongoTemplate mongo;
    @Mock EmailPayloadCipher cipher;
    @Mock EmailLinkGuard linkGuard;
    @Mock EmailTransport transport;

    private EmailOutboxWorker worker() {
        return new EmailOutboxWorker(mongo, cipher, linkGuard, transport);
    }

    private EmailJob job(int attempts) {
        return new EmailJob("job", "ciphertext", "nonce", EmailJobStatus.SENDING,
                attempts, Instant.now(), Instant.now().plusSeconds(60),
                Instant.now(), null, Instant.now().plusSeconds(3600));
    }

    @Test
    void successfulDeliveryClearsEncryptedPayload() {
        EmailPayload payload = new EmailPayload(EmailJobType.VERIFICATION, "a@example.com", "A",
                null, "https://example.test/verify?token=secret", Instant.now().plusSeconds(3600));
        when(cipher.open("job", "ciphertext", "nonce")).thenReturn(payload);
        when(linkGuard.isCurrent(payload)).thenReturn(true);
        when(mongo.exists(any(Query.class), org.mockito.ArgumentMatchers.eq(EmailJob.class)))
                .thenReturn(true);

        worker().deliver(job(1));

        verify(transport).deliver(payload);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(),
                org.mockito.ArgumentMatchers.eq(EmailJob.class));
        Document document = update.getValue().getUpdateObject();
        assertEquals("SENT", String.valueOf(((Document) document.get("$set")).get("status")));
        assertTrue(((Document) document.get("$unset")).containsKey("encryptedPayload"));
    }

    @Test
    void expiredLinkIsNotSent() {
        when(cipher.open("job", "ciphertext", "nonce")).thenReturn(new EmailPayload(
                EmailJobType.PASSWORD_RESET, "a@example.com", "A", null,
                "https://example.test/reset?token=secret", Instant.now().minusSeconds(1)));

        worker().deliver(job(1));

        verify(transport, never()).deliver(any());
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(),
                org.mockito.ArgumentMatchers.eq(EmailJob.class));
        assertEquals("FAILED", String.valueOf(((Document) update.getValue().getUpdateObject()
                .get("$set")).get("status")));
    }

    @Test
    void exhaustedLeaseCannotSendMoreThanFiveAttempts() {
        worker().deliver(job(6));
        verify(transport, never()).deliver(any());
        verify(cipher, never()).open(any(), any(), any());
    }

    @Test
    void transportFailureSchedulesRetryWithoutClearingCiphertext() {
        EmailPayload payload = new EmailPayload(EmailJobType.VERIFICATION, "a@example.com", "A",
                null, "https://example.test/verify?token=secret", Instant.now().plusSeconds(3600));
        when(cipher.open("job", "ciphertext", "nonce")).thenReturn(payload);
        when(linkGuard.isCurrent(payload)).thenReturn(true);
        when(mongo.exists(any(Query.class), org.mockito.ArgumentMatchers.eq(EmailJob.class)))
                .thenReturn(true);
        doThrow(new IllegalStateException("SMTP unavailable")).when(transport).deliver(payload);

        worker().deliver(job(1));

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(),
                org.mockito.ArgumentMatchers.eq(EmailJob.class));
        Document document = update.getValue().getUpdateObject();
        assertEquals("PENDING", String.valueOf(((Document) document.get("$set")).get("status")));
        assertEquals(null, document.get("$unset") == null ? null
                : ((Document) document.get("$unset")).get("encryptedPayload"));
    }

    @Test
    void rotatedLinkIsDiscardedWithoutSending() {
        EmailPayload payload = new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                "a@example.com", "Org", "Campaign", "https://example.test/?invitation=old",
                Instant.now().plusSeconds(3600));
        when(cipher.open("job", "ciphertext", "nonce")).thenReturn(payload);

        worker().deliver(job(1));

        verify(transport, never()).deliver(any());
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(),
                org.mockito.ArgumentMatchers.eq(EmailJob.class));
        assertEquals("FAILED", String.valueOf(((Document) update.getValue().getUpdateObject()
                .get("$set")).get("status")));
    }
}
