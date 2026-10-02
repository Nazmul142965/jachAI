package com.jachai.academy.dto;

import java.time.Instant;

public record BatchProgressRow(String learnerUserId, String assignmentId, String attemptId,
                               Integer percentage, Boolean passed, Instant submittedAt) { }
