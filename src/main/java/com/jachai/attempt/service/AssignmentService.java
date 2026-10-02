package com.jachai.attempt.service;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
import com.jachai.attempt.dto.CreateAssignmentRequest;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AssignmentService {
    private final AssignmentRepository assignments;
    private final AssessmentRepository assessments;
    private final TenantAccessService access;
    private final EntitlementService entitlements;

    public Assignment assign(String actorId, String organizationId, String assessmentId,
                             CreateAssignmentRequest request) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        access.requireRole(request.userId(), organizationId, OrganizationRole.LEARNER, OrganizationRole.CANDIDATE);
        if (!request.availableUntil().isAfter(request.availableFrom())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW", "Availability end must follow start");
        }
        Assessment assessment = assessments.findByIdAndOrganizationId(assessmentId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment not found"));
        if (assessment.status() != AssessmentStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_PUBLISHED", "Assessment is not published");
        }
        String versionId = assessment.id() + ":" + assessment.latestVersion();
        String reservation = entitlements.reserve(organizationId, UsageMetric.INVITATION);
        try {
            return assignments.save(new Assignment(UUID.randomUUID().toString(), organizationId,
                    assessment.id(), versionId, null, null, request.userId(), request.attemptsAllowed(),
                    request.availableFrom(), request.availableUntil(), Instant.now()));
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
    }
}
