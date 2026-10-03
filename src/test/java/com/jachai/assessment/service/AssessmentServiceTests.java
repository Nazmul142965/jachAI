package com.jachai.assessment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.dto.CreateAssessmentRequest;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.organization.service.OrganizationSettingsService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionDifficulty;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.model.QuestionType;
import com.jachai.question.repository.QuestionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTests {
    @Mock AssessmentRepository assessments;
    @Mock AssessmentVersionRepository versions;
    @Mock QuestionRepository questions;
    @Mock TenantAccessService access;
    @Mock EntitlementService entitlements;
    @Mock OrganizationSettingsService organizationSettings;

    private AssessmentService service() {
        return new AssessmentService(assessments, versions, questions, access, entitlements,
                organizationSettings);
    }

    @Test
    void assessmentDirectoryReadsOnlyRequestedTenantPage() {
        var pageable = PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(assessments.findByOrganizationId("org-a", pageable))
                .thenReturn(new PageImpl<Assessment>(List.of(), pageable, 41));

        var result = service().list("teacher", "org-a", null, pageable);

        assertEquals(2, result.getNumber());
        assertEquals(3, result.getTotalPages());
        verify(assessments).findByOrganizationId("org-a", pageable);
    }

    @Test
    void publishedAssessmentSelectorIsTenantScopedAndStatusFiltered() {
        var pageable = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(assessments.findByOrganizationIdAndStatus("org-b", AssessmentStatus.PUBLISHED, pageable))
                .thenReturn(new PageImpl<Assessment>(List.of(), pageable, 21));

        var result = service().list("recruiter", "org-b", AssessmentStatus.PUBLISHED, pageable);

        assertEquals(1, result.getNumber());
        assertEquals(2, result.getTotalPages());
        verify(assessments).findByOrganizationIdAndStatus("org-b", AssessmentStatus.PUBLISHED, pageable);
    }

    @Test
    void publicationFreezesApprovedQuestionAndAnswerKey() {
        var assessment = new Assessment("a1", "org-a", "teacher", "Java basics", 30, 70,
                List.of("q1"), ResultReleasePolicy.MANUAL, AssessmentStatus.DRAFT, 0, Instant.now(), null,
                "No external references. Answer every question.");
        var question = new Question("q1", "org-a", "teacher", "Java", "Which is a record?",
                QuestionType.SINGLE_CHOICE,
                List.of(new Question.Option("a", "Immutable carrier"), new Question.Option("b", "Thread")),
                Set.of("a"), QuestionStatus.APPROVED, Instant.now(), QuestionDifficulty.HARD);
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(assessment));
        when(questions.findByIdAndOrganizationId("q1", "org-a")).thenReturn(Optional.of(question));

        AssessmentVersion result = service().publish("teacher", "org-a", "a1");

        assertEquals(1, result.version());
        assertEquals(ResultReleasePolicy.MANUAL, result.resultReleasePolicy());
        assertEquals("No external references. Answer every question.", result.instructions());
        assertEquals(Set.of("a"), result.questions().getFirst().correctOptionIds());
        assertEquals(QuestionDifficulty.HARD, result.questions().getFirst().difficulty());
        ArgumentCaptor<AssessmentVersion> saved = ArgumentCaptor.forClass(AssessmentVersion.class);
        verify(versions).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
        verify(assessments).save(any(Assessment.class));
        verify(organizationSettings, never()).defaultPolicy("org-a");
    }

    @Test
    void publicationCannotReadQuestionFromAnotherTenant() {
        var assessment = new Assessment("a1", "org-a", "teacher", "Java basics", 30, 70,
                List.of("q-other"), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.DRAFT, 0, Instant.now(), null);
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(assessment));
        ApiException error = assertThrows(ApiException.class, () ->
                service().publish("teacher", "org-a", "a1"));
        assertEquals("QUESTION_MISSING", error.code());
    }

    @Test
    void exhaustedPlanStopsPublicationBeforeWritingVersion() {
        var assessment = new Assessment("a1", "org-a", "teacher", "Java basics", 30, 70,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.DRAFT, 0, Instant.now(), null);
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(assessment));
        when(entitlements.reserve("org-a", UsageMetric.PUBLISHED_ASSESSMENT))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "PLAN_LIMIT_REACHED", "Plan limit reached"));

        ApiException error = assertThrows(ApiException.class, () ->
                service().publish("teacher", "org-a", "a1"));

        assertEquals("PLAN_LIMIT_REACHED", error.code());
        verify(versions, never()).insert(any(AssessmentVersion.class));
    }

    @Test
    void failedVersionInsertRefundsReservedPlanSlot() {
        var assessment = new Assessment("a1", "org-a", "teacher", "Java basics", 30, 70,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.DRAFT, 0, Instant.now(), null);
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(assessment));
        when(entitlements.reserve("org-a", UsageMetric.PUBLISHED_ASSESSMENT)).thenReturn("bucket");
        when(versions.insert(any(AssessmentVersion.class))).thenThrow(new DuplicateKeyException("exists"));

        ApiException error = assertThrows(ApiException.class, () ->
                service().publish("teacher", "org-a", "a1"));

        assertEquals("PUBLISH_CONFLICT", error.code());
        verify(entitlements).refund("bucket");
    }

    @Test
    void newAssessmentUsesOrganizationDefaultWhenNoOverrideIsSupplied() {
        when(questions.findByIdAndOrganizationId("q1", "org-a"))
                .thenReturn(Optional.of(new Question("q1", "org-a", "teacher", "Java", "Prompt",
                        QuestionType.SINGLE_CHOICE, List.of(), Set.of(), QuestionStatus.APPROVED, Instant.now())));
        when(organizationSettings.defaultPolicy("org-a")).thenReturn(ResultReleasePolicy.MANUAL);
        when(assessments.save(any(Assessment.class))).thenAnswer(call -> call.getArgument(0));

        Assessment created = service().create("teacher", "org-a", new CreateAssessmentRequest(
                "Java", 30, 60, List.of("q1"), null));

        assertEquals(ResultReleasePolicy.MANUAL, created.resultReleasePolicy());
    }

    @Test
    void explicitAssessmentPolicyOverridesOrganizationDefault() {
        when(questions.findByIdAndOrganizationId("q1", "org-a"))
                .thenReturn(Optional.of(new Question("q1", "org-a", "teacher", "Java", "Prompt",
                        QuestionType.SINGLE_CHOICE, List.of(), Set.of(), QuestionStatus.APPROVED, Instant.now())));
        when(assessments.save(any(Assessment.class))).thenAnswer(call -> call.getArgument(0));

        Assessment created = service().create("teacher", "org-a", new CreateAssessmentRequest(
                "Java", 30, 60, List.of("q1"), ResultReleasePolicy.IMMEDIATE,
                "  You may use a calculator.  "));

        assertEquals(ResultReleasePolicy.IMMEDIATE, created.resultReleasePolicy());
        assertEquals("You may use a calculator.", created.instructions());
        verify(organizationSettings, never()).defaultPolicy("org-a");
    }

    @Test
    void revisionPublishesNewSnapshotWithoutChargingAnotherAssessmentSlot() {
        var current = new Assessment("a1", "org-a", "teacher", "Original", 30, 60,
                List.of("q1"), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.PUBLISHED, 1,
                Instant.now(), 3L, "Old instructions");
        var question = new Question("q2", "org-a", "teacher", "Java", "New prompt",
                QuestionType.SINGLE_CHOICE,
                List.of(new Question.Option("a", "Yes")), Set.of("a"), QuestionStatus.APPROVED,
                Instant.now(), QuestionDifficulty.EASY);
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(current));
        when(questions.findByIdAndOrganizationId("q2", "org-a")).thenReturn(Optional.of(question));
        when(assessments.save(any(Assessment.class))).thenAnswer(call -> call.getArgument(0));

        var version = service().reviseAndPublish("teacher", "org-a", "a1",
                new CreateAssessmentRequest(" Revised ", 45, 70, List.of("q2"), null,
                        " New instructions "));

        assertEquals("a1:2", version.id());
        assertEquals("Revised", version.title());
        assertEquals("New instructions", version.instructions());
        assertEquals(ResultReleasePolicy.IMMEDIATE, version.resultReleasePolicy());
        assertEquals("q2", version.questions().getFirst().questionId());
        ArgumentCaptor<Assessment> saved = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments).save(saved.capture());
        assertEquals(2, saved.getValue().latestVersion());
        assertEquals(3L, saved.getValue().revision());
        assertEquals(1, current.latestVersion());
        verify(entitlements, never()).reserve(any(), any());
        verify(organizationSettings, never()).defaultPolicy("org-a");
    }

    @Test
    void revisionRejectsUnchangedContentBeforeInserting() {
        var current = new Assessment("a1", "org-a", "teacher", "Original", 30, 60,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.PUBLISHED, 1,
                Instant.now(), 3L, "Rules");
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(current));

        ApiException error = assertThrows(ApiException.class, () ->
                service().reviseAndPublish("teacher", "org-a", "a1",
                        new CreateAssessmentRequest("Original", 30, 60, List.of(),
                                ResultReleasePolicy.IMMEDIATE, "Rules")));

        assertEquals("NO_CHANGES", error.code());
        verify(versions, never()).insert(any(AssessmentVersion.class));
    }

    @Test
    void concurrentRevisionDoesNotLeaveAnOrphanVersion() {
        var current = new Assessment("a1", "org-a", "teacher", "Original", 30, 60,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.PUBLISHED, 1,
                Instant.now(), 3L, "Rules");
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(current));
        when(assessments.save(any(Assessment.class)))
                .thenThrow(new OptimisticLockingFailureException("concurrent update"));

        ApiException error = assertThrows(ApiException.class, () ->
                service().reviseAndPublish("teacher", "org-a", "a1",
                        new CreateAssessmentRequest("Revised", 30, 60, List.of(),
                                ResultReleasePolicy.IMMEDIATE, "Rules")));

        assertEquals("PUBLISH_CONFLICT", error.code());
        verify(versions).deleteById("a1:2");
        verify(entitlements, never()).reserve(any(), any());
    }

    @Test
    void draftCanBeEditedWithoutPublishingOrConsumingQuota() {
        var current = new Assessment("a1", "org-a", "teacher", "Original", 30, 60,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.DRAFT, 0,
                Instant.now(), 2L, "Old rules");
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(current));
        when(assessments.save(any(Assessment.class))).thenAnswer(call -> call.getArgument(0));

        var updated = service().updateDraft("teacher", "org-a", "a1",
                new CreateAssessmentRequest(" New title ", 40, 75, List.of(), null, " New rules "));

        assertEquals("New title", updated.title());
        assertEquals("New rules", updated.instructions());
        assertEquals(0, updated.latestVersion());
        assertEquals(2L, updated.revision());
        assertEquals(ResultReleasePolicy.IMMEDIATE, updated.resultReleasePolicy());
        verify(versions, never()).insert(any(AssessmentVersion.class));
        verify(entitlements, never()).reserve(any(), any());
    }

    @Test
    void publishedAssessmentCannotBeChangedThroughDraftEndpoint() {
        var current = new Assessment("a1", "org-a", "teacher", "Original", 30, 60,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.PUBLISHED, 1,
                Instant.now(), 2L, "Rules");
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(current));

        ApiException error = assertThrows(ApiException.class, () ->
                service().updateDraft("teacher", "org-a", "a1",
                        new CreateAssessmentRequest("Change", 30, 60, List.of(), null, "Rules")));

        assertEquals("NOT_DRAFT", error.code());
        verify(assessments, never()).save(any(Assessment.class));
    }

    @Test
    void versionHistoryIsTenantScopedAndPaged() {
        var current = new Assessment("a1", "org-a", "teacher", "Original", 30, 60,
                List.of(), ResultReleasePolicy.IMMEDIATE, AssessmentStatus.PUBLISHED, 2,
                Instant.now(), 2L, "Rules");
        var pageable = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "version"));
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(current));
        when(versions.findByOrganizationIdAndAssessmentId("org-a", "a1", pageable))
                .thenReturn(new PageImpl<AssessmentVersion>(List.of(), pageable, 21));

        var result = service().listVersions("teacher", "org-a", "a1", pageable);

        assertEquals(1, result.getNumber());
        assertEquals(2, result.getTotalPages());
    }
}
