package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;

import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class EmailOutboxMongoIntegrationTests extends BaseMongoIntegrationTest {

    @Test
    void failedDeliveryWaitsForBackoffThenRetriesFromDurableState() {
        MongoTemplate mongo = new MongoTemplate(mongoClient, databaseName);
        EmailPayloadCipher cipher = mock(EmailPayloadCipher.class);
        EmailLinkGuard guard = mock(EmailLinkGuard.class);
        EmailTransport transport = mock(EmailTransport.class);
        EmailOutboxWorker worker = new EmailOutboxWorker(mongo, cipher, guard, transport);
        Instant now = Instant.now();
        String jobId = generateTestId("outbox-retry");
        trackDocumentId("email_jobs", jobId);
        mongo.insert(new EmailJob(jobId, "ciphertext", "nonce", EmailJobStatus.PENDING, 0,
                now.minusSeconds(1), null, now.minusSeconds(1), null, now.plusSeconds(3600)));
        EmailPayload payload = new EmailPayload(EmailJobType.VERIFICATION, "test@example.test", "Test",
                null, "http://127.0.0.1/verify-email.html?token=example", now.plusSeconds(3600));
        when(cipher.open(jobId, "ciphertext", "nonce")).thenReturn(payload);
        when(guard.isCurrent(payload)).thenReturn(true);
        doThrow(new IllegalStateException("temporary transport failure"))
                .doNothing().when(transport).deliver(payload);

        EmailJob firstClaim = worker.claim(now);
        assertNotNull(firstClaim);
        worker.deliver(firstClaim);
        EmailJob waiting = mongo.findById(jobId, EmailJob.class);
        assertNotNull(waiting);
        assertEquals(EmailJobStatus.PENDING, waiting.status());
        assertEquals(1, waiting.attempts());
        assertNotNull(waiting.encryptedPayload());
        assertNull(worker.claim(now.plusSeconds(30)));

        EmailJob retryClaim = worker.claim(now.plusSeconds(120));
        assertNotNull(retryClaim);
        assertEquals(2, retryClaim.attempts());
        worker.deliver(retryClaim);
        EmailJob sent = mongo.findById(jobId, EmailJob.class);
        assertNotNull(sent);
        assertEquals(EmailJobStatus.SENT, sent.status());
        assertEquals(2, sent.attempts());
        assertNotNull(sent.sentAt());
        assertNull(sent.encryptedPayload());
    }

    @Test
    void concurrentClaimHasOneWinnerAndOldLeaseCannotOverwriteReclaimedJob() throws Exception {
        MongoTemplate mongo = new MongoTemplate(mongoClient, databaseName);
        EmailPayloadCipher cipher = mock(EmailPayloadCipher.class);
        EmailLinkGuard guard = mock(EmailLinkGuard.class);
        EmailTransport transport = mock(EmailTransport.class);
        EmailOutboxWorker worker = new EmailOutboxWorker(mongo, cipher, guard, transport);
        Instant now = Instant.now();
        String jobId = generateTestId("outbox-claim");
        trackDocumentId("email_jobs", jobId);
        mongo.insert(new EmailJob(jobId, "ciphertext", "nonce", EmailJobStatus.PENDING, 0,
                now.minusSeconds(1), null, now.minusSeconds(1), null, now.plusSeconds(3600)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        EmailJob first;
        EmailJob second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> claimWhenReleased(worker, ready, start, now));
            var two = pool.submit(() -> claimWhenReleased(worker, ready, start, now));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both workers must reach the start barrier");
            start.countDown();
            first = one.get(10, TimeUnit.SECONDS);
            second = two.get(10, TimeUnit.SECONDS);
        }

        assertEquals(1, java.util.stream.Stream.of(first, second).filter(Objects::nonNull).count());
        EmailJob oldClaim = first != null ? first : second;
        assertEquals(1, oldClaim.attempts());
        EmailJob reclaimed = worker.claim(now.plusSeconds(121));
        assertNotNull(reclaimed);
        assertEquals(2, reclaimed.attempts());
        assertNull(worker.claim(now.plusSeconds(121)));

        EmailPayload payload = new EmailPayload(EmailJobType.VERIFICATION, "test@example.test", "Test",
                null, "http://127.0.0.1/verify-email.html?token=example", now.plusSeconds(3600));
        when(cipher.open(jobId, "ciphertext", "nonce")).thenReturn(payload);
        when(guard.isCurrent(payload)).thenReturn(true);
        worker.deliver(oldClaim);
        EmailJob afterStaleFinish = mongo.findById(jobId, EmailJob.class);
        assertNotNull(afterStaleFinish);
        assertEquals(EmailJobStatus.SENDING, afterStaleFinish.status());
        assertEquals(2, afterStaleFinish.attempts());

        worker.deliver(reclaimed);
        EmailJob sent = mongo.findById(jobId, EmailJob.class);
        assertNotNull(sent);
        assertEquals(EmailJobStatus.SENT, sent.status());
        assertNull(sent.encryptedPayload());
        assertNull(sent.nonce());
    }

    private EmailJob claimWhenReleased(EmailOutboxWorker worker, CountDownLatch ready,
                                       CountDownLatch start, Instant now) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim start timed out");
        return worker.claim(now);
    }
}
