package com.jachai.attempt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.assessment.model.AssessmentVersion;
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
        return new AssessmentVersion("assessment:1", "org-a", "assessment", 1, "Java", 20, 60,
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
                        "assessment:1", "learner", 1, now.minusSeconds(5), now.plusSeconds(1800), now)));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());

        AttemptView view = service().start("learner", "org-a", "assignment");

        assertEquals("q1", view.questions().getFirst().id());
        assertFalse(new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(view).contains("correctOptionIds"));
        verify(attempts).insert(any(Attempt.class));
    }

    @Test
    void submitUsesPublishedAnswerKeyAndIsServerScored() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), AttemptStatus.IN_PROGRESS,
                Map.of("q1", Set.of("a")), null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));
        when(versions.get("org-a", "assessment:1")).thenReturn(version());
        when(attempts.save(any(Attempt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResultView result = service().submit("learner", "org-a", "attempt");

        assertEquals(100, result.percentage());
        ArgumentCaptor<Attempt> saved = ArgumentCaptor.forClass(Attempt.class);
        verify(attempts).save(saved.capture());
        assertEquals(AttemptStatus.SUBMITTED, saved.getValue().status());
    }

    @Test
    void answerCannotBeChangedAfterServerDeadline() {
        Attempt attempt = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(attempt));

        ApiException error = assertThrows(ApiException.class,
                () -> service().saveAnswer("learner", "org-a", "attempt", "q1", Set.of("a")));
        assertEquals("ATTEMPT_CLOSED", error.code());
    }
}
