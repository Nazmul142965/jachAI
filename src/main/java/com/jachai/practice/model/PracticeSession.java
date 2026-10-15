package com.jachai.practice.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("practice_sessions")
@CompoundIndex(name = "practice_session_owner_created", def = "{'userId': 1, 'createdAt': -1}")
public record PracticeSession(@Id String id, String userId, String pathId, List<String> questionIds,
                              List<SessionAnswer> results, Instant createdAt,
                              @Indexed(expireAfter = "0s") Instant expiresAt) {
    public record SessionAnswer(String questionId, String selectedOptionId, boolean correct,
                                Instant answeredAt) { }
}
