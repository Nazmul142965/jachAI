package com.jachai.practice.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("practice_usage")
public record PracticeUsage(@Id String id, String userId, String utcDay, int used,
                            @Indexed(expireAfter = "0s") Instant expiresAt) { }
