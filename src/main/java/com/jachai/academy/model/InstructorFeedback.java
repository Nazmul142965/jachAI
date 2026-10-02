package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("instructor_feedback")
@CompoundIndex(name = "feedback_org_attempt_unique", def = "{'organizationId': 1, 'attemptId': 1}", unique = true)
public record InstructorFeedback(
        @Id String id,
        String organizationId,
        String attemptId,
        String learnerUserId,
        String authorUserId,
        String summary,
        String nextSteps,
        FeedbackStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        @Version Long revision) {

    public InstructorFeedback revise(String author, String newSummary, String newNextSteps, Instant now) {
        return new InstructorFeedback(id, organizationId, attemptId, learnerUserId, author,
                newSummary, newNextSteps, status, createdAt, now, publishedAt, revision);
    }

    public InstructorFeedback publish(Instant now) {
        return new InstructorFeedback(id, organizationId, attemptId, learnerUserId, authorUserId,
                summary, nextSteps, FeedbackStatus.PUBLISHED, createdAt, now, now, revision);
    }
}
