package com.jachai.academy.dto;

public record BatchAssessmentAnalyticsView(String batchAssessmentId, long activeLearners,
                                           long assignedLearners, long submittedLearners, long gradedLearners,
                                           long passedLearners, Double averageLatestPercentage,
                                           double completionRatePercent, double passRatePercent) { }
