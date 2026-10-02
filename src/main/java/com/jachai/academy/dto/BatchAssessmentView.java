package com.jachai.academy.dto;

import com.jachai.academy.model.BatchAssessment;
import java.time.Instant;

public record BatchAssessmentView(String id, String courseId, String batchId, String assessmentId,
                                  String assessmentVersionId, String assessmentTitle,
                                  int attemptsAllowed, Instant availableFrom, Instant availableUntil) {
    public static BatchAssessmentView from(BatchAssessment assessment) {
        return new BatchAssessmentView(assessment.id(), assessment.courseId(), assessment.batchId(),
                assessment.assessmentId(), assessment.assessmentVersionId(), assessment.assessmentTitle(),
                assessment.attemptsAllowed(), assessment.availableFrom(), assessment.availableUntil());
    }
}
