package com.jachai.academy.dto;

public record LearningProgressStep(String batchAssessmentId, String assessmentTitle,
                                   long activeLearners, long assignedLearners, long submittedLearners,
                                   long gradedLearners, long passedLearners,
                                   double completionRatePercent, double passRatePercent) { }
