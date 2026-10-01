package com.jachai.attempt.dto;

import com.jachai.attempt.model.Attempt;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AttemptView(String id, String assignmentId, Instant deadline,
                          List<QuestionView> questions, Map<String, Set<String>> savedAnswers) {
    public record QuestionView(String id, String topic, String prompt,
                               QuestionType type, List<Question.Option> options) { }

    public static AttemptView from(Attempt attempt, List<QuestionView> questions) {
        return new AttemptView(attempt.id(), attempt.assignmentId(), attempt.deadline(), questions,
                attempt.answers());
    }
}
