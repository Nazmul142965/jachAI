package com.jachai.attempt.dto;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import java.time.Instant;

public record AttemptSummary(String id, int attemptNumber, AttemptStatus status,
                             Instant deadline, Instant submittedAt) {
    public static AttemptSummary from(Attempt attempt) {
        return new AttemptSummary(attempt.id(), attempt.attemptNumber(), attempt.status(),
                attempt.deadline(), attempt.submittedAt());
    }
}
