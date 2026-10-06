package com.jachai.attempt.dto;

import java.time.Instant;

/**
 * A submitted attempt waiting for staff release.
 *
 * <p>{@code participantDisplayName} is additional to the id, never a replacement. It can be null
 * when the account no longer resolves, and two people can share a name, so the id stays the thing
 * staff act on and the name is what they read.
 */
public record ReviewAttemptView(String attemptId, String assignmentId, String participantUserId,
                                String participantDisplayName,
                                String assessmentTitle, int attemptNumber, Integer percentage,
                                Boolean passed, Instant submittedAt) { }
