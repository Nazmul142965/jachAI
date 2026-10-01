package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("refresh_sessions")
public record RefreshSession(
        @Id String id,
        @Indexed(unique = true) String tokenHash,
        String userId,
        Instant createdAt,
        Instant expiresAt) {
}
