package com.jachai.academy.service;

import com.jachai.academy.dto.BatchAssessmentView;
import com.jachai.academy.dto.BatchDistributionResult;
import com.jachai.academy.dto.BatchProgressRow;
import com.jachai.academy.dto.CreateBatchAssessmentRequest;
import com.jachai.academy.model.Batch;
import com.jachai.academy.model.BatchAssessment;
import com.jachai.academy.model.BatchEnrollment;
import com.jachai.academy.repository.BatchAssessmentRepository;
import com.jachai.academy.repository.BatchEnrollmentRepository;
import com.jachai.academy.repository.BatchRepository;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BatchAssessmentService {
    private final BatchAssessmentRepository batchAssessments;
    private final BatchEnrollmentRepository enrollments;
    private final BatchRepository batches;
    private final AssessmentRepository assessments;
    private final PublishedVersionStore versions;
    private final AssignmentRepository assignments;
    private final AttemptRepository attempts;
    private final OrganizationRepository organizations;
    private final TenantAccessService access;
    private final EntitlementService entitlements;

    public BatchAssessmentView create(String actorId, String organizationId, String courseId,
                                      String batchId, CreateBatchAssessmentRequest request) {
        requireStaff(actorId, organizationId);
        Batch batch = ownBatch(organizationId, courseId, batchId);
        if (!request.availableUntil().isAfter(request.availableFrom())
                || request.availableFrom().isBefore(batch.startsAt())
                || request.availableUntil().isAfter(batch.endsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW",
                    "Assessment availability must be within the batch window");
        }
        Assessment assessment = assessments.findByIdAndOrganizationId(request.assessmentId(), organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment not found"));
        if (assessment.status() != AssessmentStatus.PUBLISHED || assessment.latestVersion() < 1) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_PUBLISHED", "Publish the assessment first");
        }
        String versionId = assessment.id() + ":" + assessment.latestVersion();
        String frozenTitle = versions.get(organizationId, versionId).title();
        BatchAssessment created = new BatchAssessment(UUID.randomUUID().toString(), organizationId,
                courseId, batchId, assessment.id(), versionId, frozenTitle, request.attemptsAllowed(),
                request.availableFrom(), request.availableUntil(), actorId, Instant.now());
        try {
            batchAssessments.insert(created);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_ASSESSMENT_EXISTS",
                    "This assessment version is already assigned to the batch");
        }
        distribute(created);
        return BatchAssessmentView.from(created);
    }

    public Page<BatchAssessmentView> list(String actorId, String organizationId, String courseId,
                                          String batchId, Pageable pageable) {
        requireStaff(actorId, organizationId);
        ownBatch(organizationId, courseId, batchId);
        return batchAssessments.findByOrganizationIdAndBatchId(organizationId, batchId, pageable)
                .map(BatchAssessmentView::from);
    }

    public BatchDistributionResult reconcile(String actorId, String organizationId, String courseId,
                                             String batchId, String batchAssessmentId) {
        requireStaff(actorId, organizationId);
        ownBatch(organizationId, courseId, batchId);
        BatchAssessment assessment = ownBatchAssessment(organizationId, batchId, batchAssessmentId);
        return distribute(assessment);
    }

    public Page<BatchProgressRow> progress(String actorId, String organizationId, String courseId,
                                           String batchId, String batchAssessmentId, Pageable pageable) {
        requireStaff(actorId, organizationId);
        ownBatch(organizationId, courseId, batchId);
        ownBatchAssessment(organizationId, batchId, batchAssessmentId);
        Page<Assignment> page = assignments.findByOrganizationIdAndBatchAssessmentId(
                organizationId, batchAssessmentId, pageable);
        if (page.isEmpty()) return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        List<String> assignmentIds = page.getContent().stream().map(Assignment::id).toList();
        Map<String, Attempt> latest = attempts.findByOrganizationIdAndAssignmentIdInAndStatus(
                        organizationId, assignmentIds, AttemptStatus.SUBMITTED).stream()
                .collect(Collectors.toMap(Attempt::assignmentId, Function.identity(),
                        (a, b) -> Comparator.comparingInt(Attempt::attemptNumber).compare(a, b) >= 0 ? a : b));
        return new PageImpl<>(page.getContent().stream().map(assignment -> {
            Attempt attempt = latest.get(assignment.id());
            return new BatchProgressRow(assignment.assignedUserId(), assignment.id(),
                    attempt == null ? null : attempt.id(), attempt == null ? null : attempt.percentage(),
                    attempt == null ? null : attempt.passed(), attempt == null ? null : attempt.submittedAt());
        }).toList(), pageable, page.getTotalElements());
    }

    public void syncLearner(BatchEnrollment enrollment) {
        if (!enrollment.active()) return;
        for (BatchAssessment assessment : batchAssessments.findByOrganizationIdAndBatchId(
                enrollment.organizationId(), enrollment.batchId())) {
            ensureAssignment(assessment, enrollment.learnerUserId());
        }
    }

    private BatchDistributionResult distribute(BatchAssessment assessment) {
        int created = 0;
        int existing = 0;
        int pageNumber = 0;
        Page<BatchEnrollment> page;
        do {
            page = enrollments.findByOrganizationIdAndBatchIdAndActiveTrue(
                    assessment.organizationId(), assessment.batchId(),
                    PageRequest.of(pageNumber++, 100, Sort.by("id")));
            for (BatchEnrollment enrollment : page) {
                if (ensureAssignment(assessment, enrollment.learnerUserId())) created++;
                else existing++;
            }
        } while (page.hasNext());
        return new BatchDistributionResult(assessment.id(), created, existing);
    }

    private boolean ensureAssignment(BatchAssessment assessment, String learnerUserId) {
        String id = UUID.nameUUIDFromBytes(("batch-assessment:" + assessment.id() + ":" + learnerUserId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Assignment assignment = new Assignment(id, assessment.organizationId(), assessment.assessmentId(),
                assessment.assessmentVersionId(), null, assessment.id(), learnerUserId,
                assessment.attemptsAllowed(), assessment.availableFrom(), assessment.availableUntil(), Instant.now());
        Assignment present = assignments.findByIdAndOrganizationIdAndAssignedUserId(
                id, assessment.organizationId(), learnerUserId).orElse(null);
        if (present != null) {
            verifyExisting(assessment, present);
            return false;
        }
        String reservation = entitlements.reserve(assessment.organizationId(), UsageMetric.INVITATION);
        try {
            assignments.insert(assignment);
            return true;
        } catch (DuplicateKeyException exception) {
            entitlements.refund(reservation);
            Assignment existing = assignments.findByIdAndOrganizationIdAndAssignedUserId(
                            id, assessment.organizationId(), learnerUserId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ASSIGNMENT_CONFLICT",
                            "Assignment ID is already in use"));
            verifyExisting(assessment, existing);
            return false;
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
    }

    private void verifyExisting(BatchAssessment assessment, Assignment existing) {
        if (!assessment.id().equals(existing.batchAssessmentId())
                || !assessment.assessmentVersionId().equals(existing.assessmentVersionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSIGNMENT_CONFLICT",
                    "Assignment ID is already in use");
        }
    }

    private Batch ownBatch(String organizationId, String courseId, String batchId) {
        return batches.findByIdAndOrganizationIdAndCourseId(batchId, organizationId, courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found"));
    }

    private BatchAssessment ownBatchAssessment(String organizationId, String batchId,
                                               String batchAssessmentId) {
        return batchAssessments.findByIdAndOrganizationIdAndBatchId(
                        batchAssessmentId, organizationId, batchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch assessment not found"));
    }

    private void requireStaff(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.INSTRUCTOR);
        var organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.ACADEMY) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMY_ONLY",
                    "Batch assessments are available in Academy organizations");
        }
    }
}
