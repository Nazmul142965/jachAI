package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("password_reset_tokens")
public record PasswordResetToken(
        @Id String id,
        @Indexed(unique = true) String tokenHash,
        @Indexed String userId,
        @Indexed(expireAfter = "0s") Instant expiresAt) { }
