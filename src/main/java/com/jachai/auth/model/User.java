package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public record User(
        @Id String id,
        @Indexed(unique = true) String email,
        String displayName,
        String passwordHash,
        boolean emailVerified,
        boolean disabled,
        Instant createdAt) {

    public User verified() {
        return new User(id, email, displayName, passwordHash, true, disabled, createdAt);
    }

    public User withPasswordHash(String newPasswordHash) {
        return new User(id, email, displayName, newPasswordHash, emailVerified, disabled, createdAt);
    }
}
