package com.jachai.attempt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.ResultView;
import com.jachai.attempt.dto.ResultState;
import com.jachai.attempt.dto.StaffAttemptReviewView;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.attempt.repository.AttemptAnswerStore;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionType;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.jachai.organization.model.OrganizationRole;

@ExtendWith(MockitoExtension.class)
class AttemptServiceTests {
    @Mock AssignmentRepository assignments;
    @Mock AttemptRepository attempts;
    @Mock AttemptAnswerStore answerStore;
    @Mock PublishedVersionStore versions;
    @Mock AssessmentVersionRepository versionRepository;
    @Mock TenantAccessService access;
    @Mock UserRepository users;
    @Mock EmailSender emailSender;
    @Mock AuditEventRepository auditEvents;

    private AttemptService service() {
        return new AttemptService(assignments, attempts, answerStore, versions, versionRepository,
                access, users, emailSender, auditEvents,
                "https://jachai.example/");
    }

    @Test
    void participantAssignmentsAreTenantScopedAndPaged() {
        var pageable = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(assignments.findByOrganizationIdAndAssignedUserId("org-a", "learner", pageable))
                .thenReturn(new PageImpl<Assignment>(List.of(), pageable, 21));

        var result = service().myAssignments("learner", "org-a", pageable);

        assertEquals(1, result.getNumber());
        assertEquals(2, result.getTotalPages());
        verify(access).requireRole("learner", "org-a", OrganizationRole.LEARNER, OrganizationRole.CANDIDATE);
        verify(assignments).findByOrganizationIdAndAssignedUserId("org-a", "learner", pageable);
    }

    @Test
    void participantAssignmentsShowPublishedMetadataWithoutQuestionContent() {
        var pageable = PageRequest.of(0, 20);
        var first = new Assignment("a1", "org-a", "assessment", "assessment:1", null, null,
                "learner", 2, Instant.now(), Instant.now().plusSeconds(3600), Instant.now());
        var second = new Assignment("a2", "org-a", "assessment", "assessment:1", null, null,
                "learner", 2, Instant.now(), Instant.now().plusSeconds(3600), Instant.now());
        when(assignments.findByOrganizationIdAndAssignedUserId("org-a", "learner", pageable))
                .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));
        when(versionRepository.findByIdInAndOrganizationId(Set.of("assessment:1"), "org-a"))
                .thenReturn(List.of(version()));

        var result = service().myAssignments("learner", "org-a", pageable);

        assertEquals(2, result.getContent().size());
        assertEquals("Java", result.getContent().get(0).assessmentTitle());
        assertEquals(20, result.getContent().get(0).durationMinutes());
        assertEquals(1, result.getContent().get(0).questionCount());
        assertEquals("Java", result.getContent().get(1).assessmentTitle());
        verify(versionRepository).findByIdInAndOrganizationId(Set.of("assessment:1"), "org-a");
    }

    @Test
    void participantCanOpenOnlyTheirOwnAssignmentDetail() {
        var assignment = new Assignment("a1", "org-a", "assessment", "assessment:1", null, null,
                "learner", 2, Instant.now(), Instant.now().plusSeconds(3600), Instant.now());
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("a1", "org-a", "learner"))
                .thenReturn(Optional.of(assignment));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());

        var detail = service().myAssignment("learner", "org-a", "a1");

        assertEquals("a1", detail.id());
        assertEquals("Java", detail.assessmentTitle());
        assertEquals(20, detail.durationMinutes());
        assertEquals(1, detail.questionCount());
        verify(access).requireRole("learner", "org-a", OrganizationRole.LEARNER, OrganizationRole.CANDIDATE);
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().myAssignment("other-user", "org-a", "a1")).code());
    }

    private void recipient() {
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner", "learner@example.com",
                "Learner", null, true, false, 0, Instant.now())));
    }

    private AssessmentVersion version() {
        return version(ResultReleasePolicy.IMMEDIATE);
    }

    private AssessmentVersion version(ResultReleasePolicy policy) {
        return new AssessmentVersion("assessment:1", "org-a", "assessment", 1, "Java", 20, 60, policy,
                List.of(new AssessmentVersion.QuestionSnapshot("q1", "Java", "Which option is correct?",
                        QuestionType.SINGLE_CHOICE,
                        List.of(new Question.Option("a", "One"), new Question.Option("b", "Two")),
                        Set.of("a"))), Instant.now());
    }

    private AssessmentVersion shortVersion(ResultReleasePolicy policy) {
        return new AssessmentVersion("assessment:1", "org-a", "assessment", 1, "Java", 20, 60, policy,
                List.of(new AssessmentVersion.QuestionSnapshot("q-text", "Java", "Explain records",
                        QuestionType.SHORT_ANSWER, List.of(), Set.of())), Instant.now());
    }

    @Test
    void shortAnswerIsSavedSeparatelyFromOptionAnswers() {
        Instant now = Instant.now();
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(30), now.plusSeconds(300), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.IMMEDIATE));
        when(answerStore.saveText(attempt, "q-text", "A concise explanation")).thenReturn(true);

        service().saveTextAnswer("learner", "org-a", "attempt", "q-text", "  A concise explanation  ");

        verify(answerStore).saveText(attempt, "q-text", "A concise explanation");
        verify(attempts, never()).save(any(Attempt.class));
    }

    @Test
    void shortAnswerSubmissionWaitsForGradingAndCannotBeReleasedEarly() {
        Instant now = Instant.now();
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(30), now.plusSeconds(300), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L, Map.of("q-text", "Records are carriers"), Map.of());
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.IMMEDIATE));
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResultView pending = service().submit("learner", "org-a", "attempt");

        assertFalse(pending.released());
        assertNull(pending.percentage());
        assertEquals(ResultState.GRADING_PENDING, pending.state());
        ArgumentCaptor<Attempt> submitted = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).save(submitted.capture());
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(submitted.getValue()));
        assertEquals("MANUAL_GRADING_PENDING", assertThrows(ApiException.class,
                () -> service().releaseResult("teacher", "org-a", "attempt")).code());
        verify(emailSender, never()).sendResultReleased(any(), any(), any(), any());
    }

    @Test
    void finalManualGradeCalculatesScoreReleasesImmediatePolicyAndAudits() {
        Instant now = Instant.now();
        Attempt submitted = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(90), now.minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), now.minusSeconds(30), null, null, null, 1L,
                Map.of("q-text", "Records are carriers"), Map.of());
        AtomicReference<Attempt> stored = new AtomicReference<>(submitted);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenAnswer(invocation -> Optional.of(stored.get()));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.IMMEDIATE));
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> {
            Attempt next = invocation.getArgument(0);
            stored.set(next);
            return next;
        });
        recipient();

        StaffAttemptReviewView review = service().gradeTextAnswer("teacher", "org-a", "attempt", "q-text", true);

        assertEquals(100, review.percentage());
        assertEquals(true, review.passed());
        assertEquals(true, review.questions().getFirst().correct());
        assertEquals("teacher", review.questions().getFirst().gradedByUserId());
        assertEquals(true, review.resultReleasedAt() != null);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents, org.mockito.Mockito.atLeastOnce()).insert(event.capture());
        assertEquals(AuditAction.MANUAL_ANSWER_GRADED, event.getAllValues().getFirst().action());
        verify(emailSender).sendResultReleased("attempt", "learner@example.com", "Learner",
                "https://jachai.example/");
    }

    @Test
    void manualPolicyKeepsGradedShortAnswerPrivateUntilStaffRelease() {
        Instant now = Instant.now();
        Attempt submitted = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(90), now.minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), now.minusSeconds(30), null, null, null, 1L,
                Map.of("q-text", "Records are carriers"), Map.of());
        AtomicReference<Attempt> stored = new AtomicReference<>(submitted);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenAnswer(invocation -> Optional.of(stored.get()));
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenAnswer(invocation -> Optional.of(stored.get()));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.MANUAL));
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> {
            Attempt next = invocation.getArgument(0);
            stored.set(next);
            return next;
        });

        var review = service().gradeTextAnswer("teacher", "org-a", "attempt", "q-text", false);

        assertEquals(0, review.percentage());
        assertEquals(false, review.passed());
        assertNull(review.resultReleasedAt());
        var learnerResult = service().result("learner", "org-a", "attempt");
        assertFalse(learnerResult.released());
        assertEquals(ResultState.RELEASE_PENDING, learnerResult.state());
        verify(emailSender, never()).sendResultReleased(any(), any(), any(), any());
    }

    @Test
    void multipleAnsweredShortQuestionsStayPendingUntilEveryAnswerIsGraded() {
        Instant now = Instant.now();
        var twoQuestions = new AssessmentVersion("assessment:1", "org-a", "assessment", 1,
                "Java", 20, 50, ResultReleasePolicy.IMMEDIATE, List.of(
                new AssessmentVersion.QuestionSnapshot("q-one", "Java", "Explain records",
                        QuestionType.SHORT_ANSWER, List.of(), Set.of()),
                new AssessmentVersion.QuestionSnapshot("q-two", "Java", "Explain sealed types",
                        QuestionType.SHORT_ANSWER, List.of(), Set.of())), now);
        Attempt submitted = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(90), now.minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), now.minusSeconds(30), null, null, null, 1L,
                Map.of("q-one", "Answer one", "q-two", "Answer two"), Map.of());
        AtomicReference<Attempt> stored = new AtomicReference<>(submitted);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenAnswer(invocation -> Optional.of(stored.get()));
        when(versions.get("org-a", "assessment:1")).thenReturn(twoQuestions);
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> {
            Attempt next = invocation.getArgument(0);
            stored.set(next);
            return next;
        });
        recipient();

        var first = service().gradeTextAnswer("teacher", "org-a", "attempt", "q-one", true);
        assertNull(first.percentage());
        assertNull(first.resultReleasedAt());
        assertEquals("MANUAL_GRADING_PENDING", assertThrows(ApiException.class,
                () -> service().releaseResult("teacher", "org-a", "attempt")).code());

        var second = service().gradeTextAnswer("teacher", "org-a", "attempt", "q-two", true);
        assertEquals(100, second.percentage());
        assertEquals(true, second.passed());
        assertEquals(true, second.resultReleasedAt() != null);
    }

    @Test
    void unansweredShortQuestionIsScoredIncorrectWithoutManualWork() {
        Instant now = Instant.now();
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(30), now.plusSeconds(300), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.MANUAL));
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResultView result = service().submit("learner", "org-a", "attempt");

        assertFalse(result.released());
        ArgumentCaptor<Attempt> saved = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).save(saved.capture());
        assertEquals(0, saved.getValue().percentage());
        assertEquals(false, saved.getValue().passed());
    }

    @Test
    void gradeRequiresSubmittedTextAnswer() {
        Instant now = Instant.now();
        Attempt noText = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(90), now.minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), now.minusSeconds(30), 0, false, null, 1L);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(noText));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.MANUAL));

        assertEquals("ANSWER_MISSING", assertThrows(ApiException.class,
                () -> service().gradeTextAnswer("teacher", "org-a", "attempt", "q-text", true)).code());
        verify(attempts, never()).save(any(Attempt.class));
    }

    @Test
    void manualGradeCannotReadAnotherTenantAttempt() {
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().gradeTextAnswer("teacher", "org-b", "attempt", "q-text", true)).code());
        verify(attempts).findByIdAndOrganizationId("attempt", "org-b");
    }

    @Test
    void repeatedGradeAfterImmediateReleaseRepairsAuditWithoutChangingScore() {
        Instant now = Instant.now();
        Attempt.ManualGrade grade = new Attempt.ManualGrade(true, "teacher", now, "grade-1");
        Attempt released = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(90), now.minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), now.minusSeconds(30), 100, true, now, 2L,
                Map.of("q-text", "Records are carriers"), Map.of("q-text", grade));
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(released));
        when(versions.get("org-a", "assessment:1")).thenReturn(shortVersion(ResultReleasePolicy.IMMEDIATE));

        var review = service().gradeTextAnswer("teacher", "org-a", "attempt", "q-text", true);

        assertEquals(100, review.percentage());
        verify(attempts, never()).save(any(Attempt.class));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents, org.mockito.Mockito.atLeastOnce()).insert(event.capture());
        assertEquals("manual-grade:grade-1", event.getAllValues().getFirst().id());
    }

    @Test
    void manualGradeAuditRequiresTenantScopedAttempt() {
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().manualGradeAudit("teacher", "org-b", "attempt", 0)).code());
        verify(attempts).findByIdAndOrganizationId("attempt", "org-b");
        verify(auditEvents, never()).findByOrganizationIdAndTargetTypeAndTargetIdAndAction(
                any(), any(), any(), any(), any());
    }

    @Test
    void startReturnsQuestionsWithoutAnswerKey() throws Exception {
        Instant now = Instant.now();
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "learner"))
                .thenReturn(Optional.of(new Assignment("assignment", "org-a", "assessment",
                        "assessment:1", null, null, "learner", 1, now.minusSeconds(5), now.plusSeconds(1800), now)));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());

        AttemptView view = service().start("learner", "org-a", "assignment");

        assertEquals("q1", view.questions().getFirst().id());
        assertFalse(new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(view).contains("correctOptionIds"));
        assertEquals(Map.of(), view.savedAnswers());
        verify(attempts).insert(any(Attempt.class));
    }

    @Test
    void submitUsesPublishedAnswerKeyAndIsServerScored() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), AttemptStatus.IN_PROGRESS,
                Map.of("q1", Set.of("a")), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        recipient();

        ResultView result = service().submit("learner", "org-a", "attempt");

        assertEquals(100, result.percentage());
        assertEquals(1, result.topics().getFirst().correct());
        ArgumentCaptor<Attempt> saved = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).save(saved.capture());
        assertEquals(AttemptStatus.SUBMITTED, saved.getValue().status());
        verify(emailSender).sendResultReleased("attempt", "learner@example.com", "Learner",
                "https://jachai.example/");
    }

    @Test
    void answerCannotBeChangedAfterServerDeadline() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));

        ApiException error = assertThrows(ApiException.class,
                () -> service().saveAnswer("learner", "org-a", "attempt", "q1", Set.of("a")));
        assertEquals("ATTEMPT_CLOSED", error.code());
    }

    @Test
    void answerWriteThatLosesToSubmissionIsClosed() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(30), Instant.now().plusSeconds(300), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt), Optional.of(attempt.submitted(Instant.now(), 0, false, false)));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());

        assertEquals("ATTEMPT_CLOSED", assertThrows(ApiException.class,
                () -> service().saveAnswer("learner", "org-a", "attempt", "q1", Set.of("a"))).code());
        verify(answerStore).saveOptions(attempt, "q1", Set.of("a"));
    }

    @Test
    void answerWriteWithStaleRevisionRequiresReload() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(30), Instant.now().plusSeconds(300), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());

        assertEquals("ANSWER_CONFLICT", assertThrows(ApiException.class,
                () -> service().saveAnswer("learner", "org-a", "attempt", "q1", Set.of("a"))).code());
    }

    @Test
    void manualPolicyHidesScoreUntilStaffReleasesIt() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), AttemptStatus.IN_PROGRESS,
                Map.of("q1", Set.of("a")), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResultView pending = service().submit("learner", "org-a", "attempt");

        assertFalse(pending.released());
        assertNull(pending.percentage());
        assertEquals(List.of(), pending.topics());
        assertEquals(ResultState.RELEASE_PENDING, pending.state());
        verify(emailSender, never()).sendResultReleased(any(), any(), any(), any());

        Attempt submitted = attempt.submitted(Instant.now(), 100, true, false);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(submitted));
        recipient();
        ResultView released = service().releaseResult("teacher", "org-a", "attempt");
        assertEquals(100, released.percentage());
        assertEquals(ResultState.RELEASED, released.state());
        assertEquals("Java", released.topics().getFirst().topic());
        verify(emailSender).sendResultReleased("attempt", "learner@example.com", "Learner",
                "https://jachai.example/");
    }

    @Test
    void staffCanInspectSubmittedAnswersBeforeCandidateResultRelease() {
        Instant now = Instant.now();
        Attempt submitted = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(90), now.minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of("q1", Set.of("b")), now.minusSeconds(30), 0, false, null, 1L);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(submitted));
        when(versions.get("org-a", "assessment:1")).thenReturn(version(ResultReleasePolicy.MANUAL));

        StaffAttemptReviewView review = service().staffReview("teacher", "org-a", "attempt");

        assertEquals("learner", review.participantUserId());
        assertEquals(0, review.percentage());
        assertNull(review.resultReleasedAt());
        assertEquals(Set.of("b"), review.questions().getFirst().selectedOptionIds());
        assertEquals(Set.of("a"), review.questions().getFirst().correctOptionIds());
        assertFalse(review.questions().getFirst().correct());
        verify(attempts, never()).save(any(Attempt.class));
    }

    @Test
    void staffReviewCannotReadAnAttemptFromAnotherTenant() {
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().staffReview("teacher", "org-b", "attempt")).code());
        verify(attempts).findByIdAndOrganizationId("attempt", "org-b");
        verify(versions, never()).get(any(), any());
    }

    @Test
    void learnerCannotUseStaffAnswerReview() {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Staff only"))
                .when(access).requireRole("learner", "org-a", OrganizationRole.ORG_ADMIN,
                        OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);

        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service().staffReview("learner", "org-a", "attempt")).code());
        verify(attempts, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void unsubmittedAttemptCannotBeInspectedAsAResult() {
        Attempt inProgress = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now(), Instant.now().plusSeconds(300), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(inProgress));

        assertEquals("RESULT_PENDING", assertThrows(ApiException.class,
                () -> service().staffReview("teacher", "org-a", "attempt")).code());
        verify(versions, never()).get(any(), any());
    }

    @Test
    void notificationFailureDoesNotUndoReleasedResult() {
        Attempt submitted = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(60), Instant.now(), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), 0, false, null, 1L);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(submitted));
        when(versions.get("org-a", "assessment:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        recipient();
        doThrow(new IllegalStateException("mail unavailable")).when(emailSender)
                .sendResultReleased(any(), any(), any(), any());

        ResultView released = service().releaseResult("teacher", "org-a", "attempt");

        assertEquals(true, released.released());
        verify(attempts).save(any(Attempt.class));
    }

    @Test
    void repeatedReleaseRetriesNotificationWithoutChangingResult() {
        Instant now = Instant.now();
        Attempt released = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(60), now, AttemptStatus.SUBMITTED, Map.of(), now, 80, true, now, 2L);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(released));
        when(versions.get("org-a", "assessment:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        recipient();

        assertEquals(true, service().releaseResult("teacher", "org-a", "attempt").released());

        verify(attempts, never()).save(any(Attempt.class));
        verify(emailSender).sendResultReleased("attempt", "learner@example.com", "Learner",
                "https://jachai.example/");
    }

    @Test
    void releaseCannotReadAnotherTenantAttempt() {
        ApiException error = assertThrows(ApiException.class,
                () -> service().releaseResult("teacher", "org-a", "foreign-attempt"));
        assertEquals("NOT_FOUND", error.code());
        verify(attempts).findByIdAndOrganizationId("foreign-attempt", "org-a");
    }

    @Test
    void candidateResultEndpointKeepsManualScoreHidden() {
        Attempt submitted = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(60), Instant.now(), AttemptStatus.SUBMITTED,
                Map.of("q1", Set.of("a")), Instant.now(), 100, true, null, 1L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(submitted));
        when(versions.get("org-a", "assessment:1")).thenReturn(version(ResultReleasePolicy.MANUAL));

        ResultView view = service().result("learner", "org-a", "attempt");

        assertFalse(view.released());
        assertNull(view.passed());
        assertNull(view.percentage());
        assertEquals(List.of(), view.topics());
    }

    @Test
    void learnerCannotListAttemptsForAnotherUsersAssignment() {
        ApiException error = assertThrows(ApiException.class,
                () -> service().myAttempts("learner", "org-a", "foreign-assignment"));

        assertEquals("NOT_FOUND", error.code());
        verify(assignments).findByIdAndOrganizationIdAndAssignedUserId(
                "foreign-assignment", "org-a", "learner");
    }

    @Test
    void learnerCanFindPreviousAttemptsForOwnAssignment() {
        Instant now = Instant.now();
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "learner"))
                .thenReturn(Optional.of(new Assignment("assignment", "org-a", "assessment",
                        "assessment:1", null, null, "learner", 2, now.minusSeconds(60), now.plusSeconds(900), now)));
        Attempt finished = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(40), now.plusSeconds(20), AttemptStatus.SUBMITTED,
                Map.of("q1", Set.of("a")), now, 100, true, now, 1L);
        when(attempts.findByAssignmentIdAndOrganizationIdAndUserId("assignment", "org-a", "learner"))
                .thenReturn(List.of(finished));

        var result = service().myAttempts("learner", "org-a", "assignment");

        assertEquals("attempt", result.getFirst().id());
        assertEquals(AttemptStatus.SUBMITTED, result.getFirst().status());
    }

    @Test
    void resumedAttemptIncludesOwnSavedAnswersWithoutKey() {
        Instant now = Instant.now();
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "learner"))
                .thenReturn(Optional.of(new Assignment("assignment", "org-a", "assessment",
                        "assessment:1", null, null, "learner", 2, now.minusSeconds(60), now.plusSeconds(900), now)));
        Attempt inProgress = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(30), now.plusSeconds(600), AttemptStatus.IN_PROGRESS,
                Map.of("q1", Set.of("b")), null, null, null, null, 1L);
        when(attempts.findByAssignmentIdAndOrganizationIdAndUserId("assignment", "org-a", "learner"))
                .thenReturn(List.of(inProgress));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());

        AttemptView result = service().start("learner", "org-a", "assignment");

        assertEquals(Set.of("b"), result.savedAnswers().get("q1"));
        assertFalse(result.questions().getFirst().options().getFirst().text().contains("correctOptionIds"));
    }

    @Test
    void staffPendingReviewIsTenantScopedPaginatedAndOmitsAnswers() throws Exception {
        Instant now = Instant.now();
        Attempt pending = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                now.minusSeconds(120), now.minusSeconds(60), AttemptStatus.SUBMITTED,
                Map.of("q1", Set.of("a")), now, 100, true, null, 1L);
        PageRequest pageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "submittedAt"));
        when(attempts.findByOrganizationIdAndStatusAndResultReleasedAtIsNull(
                "org-a", AttemptStatus.SUBMITTED, pageRequest))
                .thenReturn(new PageImpl<>(List.of(pending), pageRequest, 1));
        when(versions.get("org-a", "assessment:1")).thenReturn(version(ResultReleasePolicy.MANUAL));

        var page = service().pendingReview("teacher", "org-a", 0);

        assertEquals("attempt", page.getContent().getFirst().attemptId());
        assertEquals("Java", page.getContent().getFirst().assessmentTitle());
        assertEquals(100, page.getContent().getFirst().percentage());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page.getContent().getFirst());
        assertFalse(json.contains("answers"));
        assertFalse(json.contains("correctOptionIds"));
        verify(access).requireRole("teacher", "org-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
    }

    @Test
    void pendingReviewRejectsUnboundedPageNumbers() {
        ApiException error = assertThrows(ApiException.class,
                () -> service().pendingReview("teacher", "org-a", 501));
        assertEquals("INVALID_PAGE", error.code());
    }
}
