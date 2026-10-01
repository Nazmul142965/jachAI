package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("refresh_sessions")
public record RefreshSession(
        @Id String id,
        @Indexed(unique = true) String tokenHash,
        @Indexed String userId,
        Instant createdAt,
        @Indexed(expireAfter = "0s") Instant expiresAt) {
}
