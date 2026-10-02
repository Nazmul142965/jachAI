package com.jachai.academy.dto;

public record BatchDistributionResult(String batchAssessmentId, int createdAssignments,
                                      int alreadyAssigned) { }
