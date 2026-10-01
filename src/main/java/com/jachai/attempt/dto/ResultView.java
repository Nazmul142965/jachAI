package com.jachai.attempt.dto;

import java.time.Instant;

public record ResultView(String attemptId, int percentage, boolean passed, Instant submittedAt) { }
