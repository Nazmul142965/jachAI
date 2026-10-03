package com.jachai.attempt.dto;

import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.attempt.model.Assignment;
import java.time.Instant;

/** Learner-safe assignment metadata; never exposes questions or answer keys before an attempt starts. */
public record AssignmentView(
        String id,
        String organizationId,
        String assessmentId,
        String assessmentVersionId,
        String campaignId,
        String batchAssessmentId,
        String assignedUserId,
        int attemptsAllowed,
        Instant availableFrom,
        Instant availableUntil,
        Instant createdAt,
        String assessmentTitle,
        Integer durationMinutes,
        Integer questionCount) {

    public static AssignmentView from(Assignment assignment, AssessmentVersion version) {
        return new AssignmentView(assignment.id(), assignment.organizationId(), assignment.assessmentId(),
                assignment.assessmentVersionId(), assignment.campaignId(), assignment.batchAssessmentId(),
                assignment.assignedUserId(), assignment.attemptsAllowed(), assignment.availableFrom(),
                assignment.availableUntil(), assignment.createdAt(),
                version == null ? null : version.title(),
                version == null ? null : version.durationMinutes(),
                version == null ? null : version.questions().size());
    }
}
