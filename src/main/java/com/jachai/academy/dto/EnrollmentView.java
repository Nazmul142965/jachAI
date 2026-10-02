package com.jachai.academy.dto;

import com.jachai.academy.model.BatchEnrollment;
import java.time.Instant;

public record EnrollmentView(String courseId, String courseName, String batchId, String batchName,
                             String learnerUserId,
                             Instant enrolledAt) {
    public static EnrollmentView from(BatchEnrollment enrollment) {
        return new EnrollmentView(enrollment.courseId(), enrollment.courseName(),
                enrollment.batchId(), enrollment.batchName(), enrollment.learnerUserId(),
                enrollment.enrolledAt());
    }
}
