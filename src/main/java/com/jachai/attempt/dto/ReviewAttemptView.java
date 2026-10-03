package com.jachai.attempt.dto;

import java.time.Instant;

public record ReviewAttemptView(String attemptId, String assignmentId, String participantUserId,
                                String assessmentTitle, int attemptNumber, Integer percentage,
                                Boolean passed, Instant submittedAt) { }
