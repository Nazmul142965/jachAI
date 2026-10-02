package com.jachai.attempt.dto;

import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionType;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public record StaffAttemptReviewView(String attemptId, String assignmentId, String participantUserId,
                                     String assessmentTitle, int attemptNumber, Instant submittedAt,
                                     Integer percentage, Boolean passed, Instant resultReleasedAt,
                                     List<QuestionReview> questions) {
    public record QuestionReview(String questionId, String topic, String prompt, QuestionType type,
                                 List<Question.Option> options, Set<String> selectedOptionIds,
                                 Set<String> correctOptionIds, boolean correct) { }
}
