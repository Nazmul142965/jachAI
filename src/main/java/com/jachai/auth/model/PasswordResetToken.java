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
        @Indexed(expireAfter = "0s") Instant expiresAt,
        Integer securityVersion) {
    // Existing documents lack this field; they are valid only until the account's first
    // security-version change. A new request always stores an explicit version.
    public PasswordResetToken(String id, String tokenHash, String userId, Instant expiresAt) {
        this(id, tokenHash, userId, expiresAt, null);
    }

    public boolean isCurrentFor(User user) {
        return securityVersion == null ? user.securityVersion() == 0
                : securityVersion == user.securityVersion();
    }
}
