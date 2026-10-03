package com.jachai.question.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("questions")
@CompoundIndexes({
        @CompoundIndex(name = "questions_org_created", def = "{'organizationId': 1, 'createdAt': -1, '_id': -1}"),
        @CompoundIndex(name = "questions_org_status_created", def = "{'organizationId': 1, 'status': 1, 'createdAt': -1, '_id': -1}")
})
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
        Instant createdAt,
        QuestionDifficulty difficulty) {

    public Question(String id, String organizationId, String authorUserId, String topic,
                    String prompt, QuestionType type, List<Option> options,
                    Set<String> correctOptionIds, QuestionStatus status, Instant createdAt) {
        this(id, organizationId, authorUserId, topic, prompt, type, options,
                correctOptionIds, status, createdAt, QuestionDifficulty.MEDIUM);
    }

    public record Option(String id, String text) { }

    public Question approved() {
        return new Question(id, organizationId, authorUserId, topic, prompt, type,
                options, correctOptionIds, QuestionStatus.APPROVED, createdAt, difficulty);
    }
}
