package com.jachai.talent.dto;

import com.jachai.talent.model.ReviewDecisionType;
import java.time.Instant;

public record CandidateComparison(String candidateUserId, String candidateDisplayName,
                                  String assignmentId, String attemptId,
                                  Integer percentage, Boolean passed, Instant submittedAt,
                                  ReviewDecisionType decision, String reviewerNote) { }
