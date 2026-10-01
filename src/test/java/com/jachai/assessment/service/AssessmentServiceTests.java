package com.jachai.assessment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.Question;
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

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTests {
    @Mock AssessmentRepository assessments;
    @Mock AssessmentVersionRepository versions;
    @Mock QuestionRepository questions;
    @Mock TenantAccessService access;

    @Test
    void publicationFreezesApprovedQuestionAndAnswerKey() {
        var assessment = new Assessment("a1", "org-a", "teacher", "Java basics", 30, 70,
                List.of("q1"), AssessmentStatus.DRAFT, 0, Instant.now(), null);
        var question = new Question("q1", "org-a", "teacher", "Java", "Which is a record?",
                QuestionType.SINGLE_CHOICE,
                List.of(new Question.Option("a", "Immutable carrier"), new Question.Option("b", "Thread")),
                Set.of("a"), QuestionStatus.APPROVED, Instant.now());
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(assessment));
        when(questions.findByIdAndOrganizationId("q1", "org-a")).thenReturn(Optional.of(question));

        AssessmentVersion result = new AssessmentService(assessments, versions, questions, access)
                .publish("teacher", "org-a", "a1");

        assertEquals(1, result.version());
        assertEquals(Set.of("a"), result.questions().getFirst().correctOptionIds());
        ArgumentCaptor<AssessmentVersion> saved = ArgumentCaptor.forClass(AssessmentVersion.class);
        verify(versions).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
        verify(assessments).save(any(Assessment.class));
    }

    @Test
    void publicationCannotReadQuestionFromAnotherTenant() {
        var assessment = new Assessment("a1", "org-a", "teacher", "Java basics", 30, 70,
                List.of("q-other"), AssessmentStatus.DRAFT, 0, Instant.now(), null);
        when(assessments.findByIdAndOrganizationId("a1", "org-a")).thenReturn(Optional.of(assessment));
        ApiException error = assertThrows(ApiException.class, () ->
                new AssessmentService(assessments, versions, questions, access)
                        .publish("teacher", "org-a", "a1"));
        assertEquals("QUESTION_MISSING", error.code());
    }
}
