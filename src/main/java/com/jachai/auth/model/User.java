package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
@CompoundIndex(name = "unverified_created", def = "{'emailVerified': 1, 'createdAt': 1}")
public record User(
        @Id String id,
        @Indexed(unique = true) String email,
        String displayName,
        String passwordHash,
        boolean emailVerified,
        boolean disabled,
        int securityVersion,
        Instant createdAt,
        @Indexed(unique = true, sparse = true) String googleSubject) {

    public User(String id, String email, String displayName, String passwordHash,
                boolean emailVerified, boolean disabled, int securityVersion, Instant createdAt) {
        this(id, email, displayName, passwordHash, emailVerified, disabled, securityVersion, createdAt, null);
    }

    public User verified() {
        return new User(id, email, displayName, passwordHash, true, disabled, securityVersion, createdAt, googleSubject);
    }
}
