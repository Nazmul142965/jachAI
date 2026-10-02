package com.jachai.attempt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.ResultView;
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

@ExtendWith(MockitoExtension.class)
class AttemptServiceTests {
    @Mock AssignmentRepository assignments;
    @Mock AttemptRepository attempts;
    @Mock PublishedVersionStore versions;
    @Mock TenantAccessService access;

    private AttemptService service() {
        return new AttemptService(assignments, attempts, versions, access);
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

        ResultView result = service().submit("learner", "org-a", "attempt");

        assertEquals(100, result.percentage());
        assertEquals(1, result.topics().getFirst().correct());
        ArgumentCaptor<Attempt> saved = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).save(saved.capture());
        assertEquals(AttemptStatus.SUBMITTED, saved.getValue().status());
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

        Attempt submitted = attempt.submitted(Instant.now(), 100, true, false);
        when(attempts.findByIdAndOrganizationId("attempt", "org-a")).thenReturn(Optional.of(submitted));
        ResultView released = service().releaseResult("teacher", "org-a", "attempt");
        assertEquals(100, released.percentage());
        assertEquals("Java", released.topics().getFirst().topic());
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
}
