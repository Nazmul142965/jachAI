package com.jachai.practice.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("practice_answers")
@CompoundIndex(name = "practice_user_history", def = "{'userId': 1, 'answeredAt': -1}")
public record PracticeAnswer(@Id String id, String userId, String questionId,
                             String selectedOptionId, String correctOptionId,
                             boolean correct, String explanation, Instant answeredAt,
                             @Indexed(expireAfter = "0s") Instant expiresAt) { }
