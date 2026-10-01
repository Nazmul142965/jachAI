package com.jachai.question.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("questions")
@CompoundIndex(name = "questions_org_status", def = "{'organizationId': 1, 'status': 1}")
public record Question(
        @Id String id,
        String organizationId,
        String authorUserId,
        String topic,
        String prompt,
        QuestionType type,
        List<Option> options,
        Set<String> correctOptionIds,
        QuestionStatus status,
        Instant createdAt) {

    public record Option(String id, String text) { }

    public Question approved() {
        return new Question(id, organizationId, authorUserId, topic, prompt, type,
                options, correctOptionIds, QuestionStatus.APPROVED, createdAt);
    }
}
