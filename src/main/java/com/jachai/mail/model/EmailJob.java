package com.jachai.mail.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("email_jobs")
@CompoundIndex(name = "mail_due", def = "{'status': 1, 'nextAttemptAt': 1, 'leaseUntil': 1}")
public record EmailJob(@Id String id, String encryptedPayload, String nonce,
                       EmailJobStatus status, int attempts,
                       Instant nextAttemptAt, Instant leaseUntil, Instant createdAt,
                       Instant sentAt, @Indexed(expireAfter = "0s") Instant expiresAt) { }
