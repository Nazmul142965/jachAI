package com.jachai.platform.dto;

import com.jachai.assessment.model.AssessmentStatus;

public record AssessmentSupportPreview(
        String organizationId,
        String assessmentId,
        String title,
        AssessmentStatus status,
        int latestVersion,
        int questionCount,
        int durationMinutes,
        String accessEventId) { }
