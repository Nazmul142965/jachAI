package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("revoked_access_tokens")
public record RevokedAccessToken(@Id String jti,
                                 @Indexed(expireAfter = "0s") Instant expiresAt) { }
