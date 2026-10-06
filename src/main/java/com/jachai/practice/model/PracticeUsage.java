package com.jachai.practice.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("practice_usage")
public record PracticeUsage(@Id String id, String userId, String utcDay, int used, int generated,
                            @Indexed(expireAfter = "0s") Instant expiresAt) {
    public PracticeUsage(String id, String userId, String utcDay, int used, Instant expiresAt) {
        this(id, userId, utcDay, used, 0, expiresAt);
    }
}
