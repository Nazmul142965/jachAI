package com.jachai.academy.dto;

import com.jachai.academy.model.FeedbackStatus;
import com.jachai.academy.model.InstructorFeedback;
import java.time.Instant;

/**
 * Instructor feedback on one attempt, as both the learner and the staff screens read it.
 *
 * <p>The display names are additional to the ids, never replacements. A learner reading feedback
 * needs to know which instructor wrote it, and a user id does not tell them; staff screens act on
 * the id. Either name can be null when the account no longer resolves, so neither is relied on.
 */
public record FeedbackView(String attemptId, String learnerUserId, String learnerDisplayName,
                           String authorUserId, String authorDisplayName,
                           String summary, String nextSteps, FeedbackStatus status,
                           Instant updatedAt, Instant publishedAt) {
    public static FeedbackView from(InstructorFeedback feedback, String learnerDisplayName,
                                    String authorDisplayName) {
        return new FeedbackView(feedback.attemptId(), feedback.learnerUserId(), learnerDisplayName,
                feedback.authorUserId(), authorDisplayName, feedback.summary(),
                feedback.nextSteps(), feedback.status(), feedback.updatedAt(),
                feedback.publishedAt());
    }
}
