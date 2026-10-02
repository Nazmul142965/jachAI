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
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.ResultView;
import com.jachai.attempt.dto.StaffAttemptReviewView;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionType;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    @Mock PublishedVersionStore versions;
    @Mock TenantAccessService access;
    @Mock UserRepository users;
    @Mock EmailSender emailSender;

    private AttemptService service() {
        return new AttemptService(assignments, attempts, versions, access, users, emailSender,
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
        verify(emailSender, never()).sendResultReleased(any(), any(), any(), any());

        Attempt submitted = attempt.submitted(Instant.now(), 100, true, false);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(submitted));
        recipient();
        ResultView released = service().releaseResult("teacher", "org-a", "attempt");
        assertEquals(100, released.percentage());
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
