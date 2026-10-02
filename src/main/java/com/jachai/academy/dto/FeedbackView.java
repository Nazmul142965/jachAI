package com.jachai.academy.dto;

import com.jachai.academy.model.FeedbackStatus;
import com.jachai.academy.model.InstructorFeedback;
import java.time.Instant;

public record FeedbackView(String attemptId, String learnerUserId, String authorUserId,
                           String summary, String nextSteps, FeedbackStatus status,
                           Instant updatedAt, Instant publishedAt) {
    public static FeedbackView from(InstructorFeedback feedback) {
        return new FeedbackView(feedback.attemptId(), feedback.learnerUserId(),
                feedback.authorUserId(), feedback.summary(), feedback.nextSteps(),
                feedback.status(), feedback.updatedAt(), feedback.publishedAt());
    }
}
