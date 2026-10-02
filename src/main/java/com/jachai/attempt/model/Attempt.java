package com.jachai.attempt.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("attempts")
@CompoundIndexes({
        @CompoundIndex(name = "attempt_assignment_no_unique",
                def = "{'assignmentId': 1, 'attemptNumber': 1}", unique = true),
        @CompoundIndex(name = "attempt_org_user_status",
                def = "{'organizationId': 1, 'userId': 1, 'status': 1}"),
        @CompoundIndex(name = "attempt_org_assignment_status",
                def = "{'organizationId': 1, 'assignmentId': 1, 'status': 1}"),
        @CompoundIndex(name = "attempt_review_queue",
                def = "{'organizationId': 1, 'status': 1, 'resultReleasedAt': 1, 'submittedAt': -1}")
})
public record Attempt(
        @Id String id,
        String organizationId,
        String assignmentId,
        String assessmentVersionId,
        String userId,
        int attemptNumber,
        Instant startedAt,
        Instant deadline,
        AttemptStatus status,
        Map<String, Set<String>> answers,
        Instant submittedAt,
        Integer percentage,
        Boolean passed,
        Instant resultReleasedAt,
        @Version Long revision) {

    public Attempt withAnswer(String questionId, Set<String> selected) {
        var next = new java.util.HashMap<>(answers);
        next.put(questionId, Set.copyOf(selected));
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, status, Map.copyOf(next), submittedAt,
                percentage, passed, resultReleasedAt, revision);
    }

    public Attempt submitted(Instant when, int score, boolean pass, boolean releaseImmediately) {
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, AttemptStatus.SUBMITTED, answers,
                when, score, pass, releaseImmediately ? when : null, revision);
    }

    public Attempt releaseResult(Instant when) {
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, status, answers,
                submittedAt, percentage, passed, when, revision);
    }
}
