package com.jachai.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.ai.dto.TutorRequest;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
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
class TutorServiceTests {
    @Mock TenantAccessService access;
    @Mock AttemptRepository attempts;
    @Mock PublishedVersionStore versions;
    @Mock AiQuotaService quota;
    @Mock AiService ai;

    private TutorService service() {
        return new TutorService(access, attempts, versions, quota, ai);
    }

    private Attempt submitted() {
        return new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60),
                AttemptStatus.SUBMITTED, Map.of(), Instant.now(), 50, false, Instant.now(), 1L);
    }

    @Test
    void activeRetryBlocksTutor() {
        Attempt active = new Attempt("retry", "org-a", "assignment", "assessment:1", "learner", 2,
                Instant.now(), Instant.now().plusSeconds(600), AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, 0L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(submitted()));
        when(attempts.findByOrganizationIdAndUserIdAndStatus("org-a", "learner", AttemptStatus.IN_PROGRESS))
                .thenReturn(List.of(active));

        ApiException error = assertThrows(ApiException.class, () -> service().explain("learner", "org-a",
                "attempt", new TutorRequest("Explain records")));

        assertEquals("TUTOR_NOT_READY", error.code());
        verify(ai, never()).generate(any(), any(), any());
    }

    @Test
    void tutorPromptContainsTopicsButNotAnswerKeys() {
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(submitted()));
        when(versions.get("org-a", "assessment:1")).thenReturn(new AssessmentVersion(
                "assessment:1", "org-a", "assessment", 1, "Java", 20, 60, ResultReleasePolicy.IMMEDIATE,
                List.of(new AssessmentVersion.QuestionSnapshot("q1", "Records", "Secret question",
                        QuestionType.SINGLE_CHOICE, List.of(new Question.Option("SECRET_KEY", "Secret answer")),
                        Set.of("SECRET_KEY"))), Instant.now()));
        when(ai.generate(eq(AiUseCase.TUTOR), any(), any())).thenReturn("Records are data carriers.");

        assertEquals("Records are data carriers.", service().explain("learner", "org-a", "attempt",
                new TutorRequest("Explain records")).explanation());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).generate(eq(AiUseCase.TUTOR), any(), prompt.capture());
        assertFalse(prompt.getValue().contains("SECRET_KEY"));
        assertFalse(prompt.getValue().contains("Secret question"));
    }

    @Test
    void unreleasedManualResultBlocksTutorBeforeAiUsage() {
        Attempt pending = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), 50, false, null, 1L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(pending));
        when(versions.get("org-a", "assessment:1")).thenReturn(new AssessmentVersion(
                "assessment:1", "org-a", "assessment", 1, "Java", 20, 60,
                ResultReleasePolicy.MANUAL, List.of(), Instant.now()));

        ApiException error = assertThrows(ApiException.class, () -> service().explain("learner", "org-a",
                "attempt", new TutorRequest("Explain records")));

        assertEquals("TUTOR_NOT_READY", error.code());
        verify(quota, never()).consume(any(), any());
        verify(ai, never()).generate(any(), any(), any());
    }

    @Test
    void ungradedImmediatePolicyShortAnswerBlocksTutorBeforeAiUsage() {
        Attempt pending = new Attempt("attempt", "org-a", "assignment", "assessment:1", "learner", 1,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), null, null, null, 1L);
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "learner"))
                .thenReturn(Optional.of(pending));
        when(versions.get("org-a", "assessment:1")).thenReturn(new AssessmentVersion(
                "assessment:1", "org-a", "assessment", 1, "Java", 20, 60,
                ResultReleasePolicy.IMMEDIATE, List.of(), Instant.now()));

        assertEquals("TUTOR_NOT_READY", assertThrows(ApiException.class,
                () -> service().explain("learner", "org-a", "attempt",
                        new TutorRequest("Explain records"))).code());
        verify(quota, never()).consume(any(), any());
        verify(ai, never()).generate(any(), any(), any());
    }
}
