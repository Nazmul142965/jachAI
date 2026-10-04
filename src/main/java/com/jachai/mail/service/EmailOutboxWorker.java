package com.jachai.mail.service;

import com.jachai.mail.model.EmailJob;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.model.EmailPayload;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class EmailOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_BATCH = 20;

    private final MongoTemplate mongo;
    private final EmailPayloadCipher cipher;
    private final EmailLinkGuard linkGuard;
    private final EmailTransport transport;

    @Scheduled(fixedDelayString = "${app.mail.outbox.poll-ms:15000}")
    public void poll() {
        for (int i = 0; i < MAX_BATCH; i++) {
            EmailJob job;
            try {
                job = claim(Instant.now());
            } catch (RuntimeException exception) {
                log.warn("Email outbox is unavailable: {}", exception.getClass().getSimpleName());
                return;
            }
            if (job == null) return;
            deliver(job);
        }
    }

    EmailJob claim(Instant now) {
        Criteria due = new Criteria().orOperator(
                Criteria.where("status").is(EmailJobStatus.PENDING).and("nextAttemptAt").lte(now),
                Criteria.where("status").is(EmailJobStatus.SENDING).and("leaseUntil").lte(now));
        Query query = new Query(due).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        Update update = new Update().set("status", EmailJobStatus.SENDING)
                .set("leaseUntil", now.plus(LEASE)).inc("attempts", 1);
        return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), EmailJob.class);
    }

    void deliver(EmailJob job) {
        Instant now = Instant.now();
        if (job.attempts() > MAX_ATTEMPTS) {
            finish(job, EmailJobStatus.FAILED, now);
            return;
        }
        EmailPayload payload;
        try {
            payload = cipher.open(job.id(), job.encryptedPayload(), job.nonce());
        } catch (RuntimeException exception) {
            finish(job, EmailJobStatus.FAILED, now);
            log.warn("Email job {} cannot be decrypted; marked failed", job.id());
            return;
        }
        if (payload.validUntil() == null || !payload.validUntil().isAfter(now)) {
            finish(job, EmailJobStatus.FAILED, now);
            return;
        }
        try {
            if (!linkGuard.isCurrent(payload)) {
                finish(job, EmailJobStatus.FAILED, Instant.now());
                return;
            }
        } catch (RuntimeException exception) {
            scheduleFailure(job, exception);
            return;
        }
        try {
            // A slow worker may have lost its lease while validating the payload. Do not
            // send from that stale claim after another worker has reclaimed the job.
            if (!mongo.exists(owned(job).addCriteria(Criteria.where("leaseUntil").gt(Instant.now())),
                    EmailJob.class)) return;
            transport.deliver(payload);
        } catch (RuntimeException exception) {
            scheduleFailure(job, exception);
            return;
        }
        finish(job, EmailJobStatus.SENT, Instant.now());
    }

    private void scheduleFailure(EmailJob job, RuntimeException exception) {
        if (job.attempts() >= MAX_ATTEMPTS) {
            finish(job, EmailJobStatus.FAILED, Instant.now());
        } else {
            retry(job, Instant.now().plus(backoff(job.attempts())));
        }
        log.warn("Email job {} delivery attempt {} failed: {}",
                job.id(), job.attempts(), exception.getClass().getSimpleName());
    }

    private void retry(EmailJob job, Instant nextAttemptAt) {
        mongo.updateFirst(owned(job), new Update().set("status", EmailJobStatus.PENDING)
                .set("nextAttemptAt", nextAttemptAt).unset("leaseUntil"), EmailJob.class);
    }

    private void finish(EmailJob job, EmailJobStatus status, Instant now) {
        Update update = new Update().set("status", status).unset("encryptedPayload")
                .unset("nonce").unset("leaseUntil");
        if (status == EmailJobStatus.SENT) update.set("sentAt", now);
        mongo.updateFirst(owned(job), update, EmailJob.class);
    }

    private Query owned(EmailJob job) {
        return Query.query(Criteria.where("_id").is(job.id())
                .and("status").is(EmailJobStatus.SENDING).and("attempts").is(job.attempts()));
    }

    private Duration backoff(int attempt) {
        return switch (attempt) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            default -> Duration.ofMinutes(30);
        };
    }
}
