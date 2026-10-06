package com.jachai.practice.model;

import com.jachai.practice.service.PracticeCatalog.Option;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("personal_practice_questions")
@CompoundIndex(name = "personal_practice_owner_created", def = "{'userId': 1, 'createdAt': -1}")
public record PersonalPracticeQuestion(@Id String id, String userId, String topic, String prompt,
                                       List<Option> options, String correctOptionId, String explanation,
                                       Instant createdAt, @Indexed(expireAfter = "0s") Instant expiresAt) { }
