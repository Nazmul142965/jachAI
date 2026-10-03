package com.jachai.academy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.academy.dto.CreateBatchAssessmentRequest;
import com.jachai.academy.model.Batch;
import com.jachai.academy.model.BatchAssessment;
import com.jachai.academy.model.BatchEnrollment;
import com.jachai.academy.repository.BatchAssessmentRepository;
import com.jachai.academy.repository.BatchAnalyticsReader;
import com.jachai.academy.repository.BatchEnrollmentRepository;
import com.jachai.academy.repository.BatchRepository;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.billing.service.EntitlementService;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class BatchAssessmentServiceTests {
    @Mock BatchAssessmentRepository batchAssessments;
    @Mock BatchAnalyticsReader analyticsReader;
    @Mock BatchEnrollmentRepository enrollments;
    @Mock BatchRepository batches;
    @Mock AssessmentRepository assessments;
    @Mock PublishedVersionStore versions;
    @Mock AssignmentRepository assignments;
    @Mock AttemptRepository attempts;
    @Mock OrganizationRepository organizations;
    @Mock TenantAccessService access;
    @Mock EntitlementService entitlements;
    BatchAssessmentService service;

    @BeforeEach
    void setUp() {
        service = new BatchAssessmentService(batchAssessments, analyticsReader, enrollments, batches, assessments,
                versions, assignments, attempts, organizations, access, entitlements);
    }

    @Test
    void freezesPublishedVersionAndAssignsEveryEnrolledLearner() {
        academy();
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(assessments.findByIdAndOrganizationId("assessment-a", "org-a"))
                .thenReturn(Optional.of(assessment()));
        when(versions.get("org-a", "assessment-a:2")).thenReturn(version());
        when(enrollments.findByOrganizationIdAndBatchIdAndActiveTrue(
                eq("org-a"), eq("batch-a"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(enrollment())));

        var result = service.create("teacher", "org-a", "course-a", "batch-a", request());

        assertEquals("assessment-a:2", result.assessmentVersionId());
        assertEquals("Version two", result.assessmentTitle());
        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignments).insert(saved.capture());
        assertEquals("learner", saved.getValue().assignedUserId());
        assertEquals(result.id(), saved.getValue().batchAssessmentId());
        assertEquals("org-a", saved.getValue().organizationId());
    }

    @Test
    void foreignBatchCannotBeAssigned() {
        academy();

        ApiException error = assertThrows(ApiException.class,
                () -> service.create("teacher", "org-a", "course-a", "foreign", request()));

        assertEquals("NOT_FOUND", error.code());
        verify(assessments, never()).findByIdAndOrganizationId(any(), any());
        verify(batchAssessments, never()).insert(any(BatchAssessment.class));
    }

    @Test
    void availabilityMustStayInsideBatchWindow() {
        academy();
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        var request = new CreateBatchAssessmentRequest("assessment-a", 1,
                Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.create("teacher", "org-a", "course-a", "batch-a", request));

        assertEquals("INVALID_WINDOW", error.code());
    }

    @Test
    void lateEnrollmentReceivesExistingBatchAssessment() {
        when(batchAssessments.findByOrganizationIdAndBatchId("org-a", "batch-a"))
                .thenReturn(List.of(batchAssessment()));

        service.syncLearner(enrollment());

        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignments).insert(saved.capture());
        assertEquals("ba-a", saved.getValue().batchAssessmentId());
        assertEquals("learner", saved.getValue().assignedUserId());
    }

    @Test
    void duplicateDistributionIsIdempotentWhenAssignmentMatches() {
        when(batchAssessments.findByOrganizationIdAndBatchId("org-a", "batch-a"))
                .thenReturn(List.of(batchAssessment()));
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId(
                any(), eq("org-a"), eq("learner")))
                .thenAnswer(call -> Optional.of(new Assignment(call.getArgument(0), "org-a",
                        "assessment-a", "assessment-a:2", null, "ba-a", "learner", 2,
                        request().availableFrom(), request().availableUntil(), Instant.now())));

        service.syncLearner(enrollment());

        verify(assignments).findByIdAndOrganizationIdAndAssignedUserId(any(), eq("org-a"), eq("learner"));
        verify(entitlements, never()).reserve(any(), any());
    }

    @Test
    void reconcileRepairsMissingAssignmentsForAnExistingBatchAssessment() {
        academy();
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(batchAssessments.findByIdAndOrganizationIdAndBatchId("ba-a", "org-a", "batch-a"))
                .thenReturn(Optional.of(batchAssessment()));
        when(enrollments.findByOrganizationIdAndBatchIdAndActiveTrue(
                eq("org-a"), eq("batch-a"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(enrollment())));

        var result = service.reconcile("teacher", "org-a", "course-a", "batch-a", "ba-a");

        assertEquals(1, result.createdAssignments());
        assertEquals(0, result.alreadyAssigned());
        verify(assignments).insert(any(Assignment.class));
    }

    @Test
    void progressReportsLatestSubmittedAttemptForEachLearner() {
        academy();
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(batchAssessments.findByIdAndOrganizationIdAndBatchId("ba-a", "org-a", "batch-a"))
                .thenReturn(Optional.of(batchAssessment()));
        var pageable = PageRequest.of(0, 20);
        Assignment assignment = new Assignment("assignment-a", "org-a", "assessment-a",
                "assessment-a:2", null, "ba-a", "learner", 2, request().availableFrom(),
                request().availableUntil(), Instant.now());
        when(assignments.findByOrganizationIdAndBatchAssessmentId("org-a", "ba-a", pageable))
                .thenReturn(new PageImpl<>(List.of(assignment), pageable, 1));
        when(attempts.findByOrganizationIdAndAssignmentIdInAndStatus(
                "org-a", List.of("assignment-a"), AttemptStatus.SUBMITTED))
                .thenReturn(List.of(attempt(1, 60), attempt(2, 90)));

        var page = service.progress("teacher", "org-a", "course-a", "batch-a", "ba-a", pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals(90, page.getContent().getFirst().percentage());
        assertTrue(page.getContent().getFirst().passed());
        verify(access).requireRole("teacher", "org-a", OrganizationRole.ORG_ADMIN, OrganizationRole.INSTRUCTOR);
    }

    @Test
    void analyticsUsesActiveCohortDenominatorAndLatestAttemptTotals() {
        academy();
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(batchAssessments.findByIdAndOrganizationIdAndBatchId("ba-a", "org-a", "batch-a"))
                .thenReturn(Optional.of(batchAssessment()));
        when(enrollments.countByOrganizationIdAndBatchIdAndActiveTrue("org-a", "batch-a"))
                .thenReturn(10L);
        when(analyticsReader.totals("org-a", "ba-a"))
                .thenReturn(new BatchAnalyticsReader.Totals(9, 6, 5, 4, 72.5));

        var result = service.analytics("teacher", "org-a", "course-a", "batch-a", "ba-a");

        assertEquals(10, result.activeLearners());
        assertEquals(9, result.assignedLearners());
        assertEquals(6, result.submittedLearners());
        assertEquals(5, result.gradedLearners());
        assertEquals(60.0, result.completionRatePercent());
        assertEquals(80.0, result.passRatePercent());
        assertEquals(72.5, result.averageLatestPercentage());
    }

    @Test
    void pendingManualGradesDoNotBecomeFailedLearners() {
        academy();
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(batchAssessments.findByIdAndOrganizationIdAndBatchId("ba-a", "org-a", "batch-a"))
                .thenReturn(Optional.of(batchAssessment()));
        when(enrollments.countByOrganizationIdAndBatchIdAndActiveTrue("org-a", "batch-a"))
                .thenReturn(5L);
        when(analyticsReader.totals("org-a", "ba-a"))
                .thenReturn(new BatchAnalyticsReader.Totals(5, 3, 0, 0, null));

        var result = service.analytics("teacher", "org-a", "course-a", "batch-a", "ba-a");

        assertEquals(3, result.submittedLearners());
        assertEquals(0, result.gradedLearners());
        assertEquals(60.0, result.completionRatePercent());
        assertEquals(0.0, result.passRatePercent());
        assertNull(result.averageLatestPercentage());
    }

    @Test
    void analyticsRejectsForeignBatchBeforeDatabaseAggregation() {
        academy();

        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service.analytics("teacher", "org-a", "course-a", "foreign", "ba-a")).code());
        verify(analyticsReader, never()).totals(any(), any());
    }

    private void academy() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Institute", OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    private Batch batch() {
        return new Batch("batch-a", "org-a", "course-a", "Fall", "fall",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"),
                "teacher", Instant.now());
    }

    private CreateBatchAssessmentRequest request() {
        return new CreateBatchAssessmentRequest("assessment-a", 2,
                Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"));
    }

    private Assessment assessment() {
        return new Assessment("assessment-a", "org-a", "teacher", "Current title", 30, 60,
                List.of("q1"), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.PUBLISHED,
                2, Instant.now(), 0L);
    }

    private AssessmentVersion version() {
        return new AssessmentVersion("assessment-a:2", "org-a", "assessment-a", 2,
                "Version two", 30, 60, ResultReleasePolicy.IMMEDIATE, List.of(), Instant.now());
    }

    private BatchAssessment batchAssessment() {
        return new BatchAssessment("ba-a", "org-a", "course-a", "batch-a", "assessment-a",
                "assessment-a:2", "Version two", 2, request().availableFrom(),
                request().availableUntil(), "teacher", Instant.now());
    }

    private BatchEnrollment enrollment() {
        return new BatchEnrollment("enrollment-a", "org-a", "course-a", "batch-a", "Java", "Fall",
                "learner", true, Instant.now());
    }

    private Attempt attempt(int number, int percentage) {
        return new Attempt("attempt-" + number, "org-a", "assignment-a", "assessment-a:2",
                "learner", number, Instant.now(), Instant.now(), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), percentage, percentage >= 60, Instant.now(), 0L);
    }
}
