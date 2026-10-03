package com.jachai.attempt.service;

import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AssignmentReminderService {
    private final AssignmentRepository assignments;
    private final AssessmentVersionRepository versions;
    private final AttemptRepository attempts;
    private final UserRepository users;
    private final TenantAccessService access;
    private final EmailSender emailSender;
    @Value("${app.public-base-url}") private String publicBaseUrl;

    public boolean queue(String actorId, String organizationId, String assignmentId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        var assignment = assignments.findByIdAndOrganizationId(assignmentId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assignment not found"));
        if (!assignment.availableUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSIGNMENT_EXPIRED", "Assignment availability has ended");
        }
        access.requireRole(assignment.assignedUserId(), organizationId,
                OrganizationRole.LEARNER, OrganizationRole.CANDIDATE);
        if (attempts.existsByOrganizationIdAndAssignmentIdAndStatus(
                organizationId, assignment.id(), AttemptStatus.SUBMITTED)) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSIGNMENT_COMPLETED", "This assignment has a submitted attempt");
        }
        var user = users.findById(assignment.assignedUserId())
                .filter(value -> value.emailVerified() && !value.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "RECIPIENT_UNAVAILABLE",
                        "Recipient account is not verified and active"));
        var version = versions.findByIdAndOrganizationId(assignment.assessmentVersionId(), organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ASSESSMENT_VERSION_MISSING",
                        "Published assessment version is unavailable"));
        return emailSender.sendAssignmentReminder(assignment.id(), user.email(), user.displayName(),
                version.title(), publicBaseUrl.replaceAll("/+$", "") + "/", assignment.availableUntil());
    }
}
