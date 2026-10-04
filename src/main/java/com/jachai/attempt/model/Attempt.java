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
        @CompoundIndex(name = "attempt_org_user_active_deadline",
                def = "{'organizationId': 1, 'userId': 1, 'status': 1, 'deadline': 1}"),
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
        @Version Long revision,
        Map<String, String> textAnswers,
        Map<String, ManualGrade> manualGrades) {

    public record ManualGrade(boolean correct, String actorUserId, Instant gradedAt, String changeId) { }

    public Attempt {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        textAnswers = textAnswers == null ? Map.of() : Map.copyOf(textAnswers);
        manualGrades = manualGrades == null ? Map.of() : Map.copyOf(manualGrades);
    }

    public Attempt(String id, String organizationId, String assignmentId, String assessmentVersionId,
                   String userId, int attemptNumber, Instant startedAt, Instant deadline,
                   AttemptStatus status, Map<String, Set<String>> answers, Instant submittedAt,
                   Integer percentage, Boolean passed, Instant resultReleasedAt, Long revision) {
        this(id, organizationId, assignmentId, assessmentVersionId, userId, attemptNumber,
                startedAt, deadline, status, answers, submittedAt, percentage, passed,
                resultReleasedAt, revision, Map.of(), Map.of());
    }

    public Attempt withAnswer(String questionId, Set<String> selected) {
        var next = new java.util.HashMap<>(answers);
        next.put(questionId, Set.copyOf(selected));
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, status, Map.copyOf(next), submittedAt,
                percentage, passed, resultReleasedAt, revision, textAnswers, manualGrades);
    }

    public Attempt withTextAnswer(String questionId, String answer) {
        var next = new java.util.HashMap<>(textAnswers);
        next.put(questionId, answer);
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, status, answers, submittedAt,
                percentage, passed, resultReleasedAt, revision, Map.copyOf(next), manualGrades);
    }

    public Attempt submitted(Instant when, Integer score, Boolean pass, boolean releaseImmediately) {
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, AttemptStatus.SUBMITTED, answers,
                when, score, pass, releaseImmediately ? when : null, revision, textAnswers, manualGrades);
    }

    public Attempt graded(String questionId, ManualGrade grade, Integer score, Boolean pass,
                          Instant releaseAt) {
        var next = new java.util.HashMap<>(manualGrades);
        next.put(questionId, grade);
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, status, answers, submittedAt,
                score, pass, releaseAt, revision, textAnswers, Map.copyOf(next));
    }

    public Attempt releaseResult(Instant when) {
        return new Attempt(id, organizationId, assignmentId, assessmentVersionId, userId,
                attemptNumber, startedAt, deadline, status, answers,
                submittedAt, percentage, passed, when, revision, textAnswers, manualGrades);
    }
}
