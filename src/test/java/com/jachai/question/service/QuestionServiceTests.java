package com.jachai.question.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.model.QuestionType;
import com.jachai.question.dto.CreateQuestionRequest;
import com.jachai.shared.error.ApiException;
import com.jachai.question.repository.QuestionRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTests {
    @Mock QuestionRepository questions;
    @Mock TenantAccessService access;

    @Test
    void shortAnswerDraftHasNoOptionsOrAnswerKey() {
        var input = new CreateQuestionRequest("Java", "Explain records", QuestionType.SHORT_ANSWER,
                List.of(), Set.of());
        when(questions.save(any(Question.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Question saved = new QuestionService(questions, access).create("teacher", "org-a", input);

        assertEquals(QuestionType.SHORT_ANSWER, saved.type());
        assertEquals(List.of(), saved.options());
        assertEquals(Set.of(), saved.correctOptionIds());
    }

    @Test
    void shortAnswerRejectsHiddenObjectiveAnswerKey() {
        var input = new CreateQuestionRequest("Java", "Explain records", QuestionType.SHORT_ANSWER,
                List.of(new CreateQuestionRequest.OptionInput("A", "Answer")), Set.of("A"));

        assertEquals("INVALID_QUESTION", assertThrows(ApiException.class,
                () -> new QuestionService(questions, access).create("teacher", "org-a", input)).code());
    }

    @Test
    void objectiveQuestionStillRequiresAtLeastTwoOptions() {
        var input = new CreateQuestionRequest("Java", "What is a record?", QuestionType.SINGLE_CHOICE,
                List.of(new CreateQuestionRequest.OptionInput("A", "Data carrier")), Set.of("A"));

        assertEquals("INVALID_QUESTION", assertThrows(ApiException.class,
                () -> new QuestionService(questions, access).create("teacher", "org-a", input)).code());
    }

    @Test
    void questionBankReadsOnlyRequestedTenantPage() {
        var pageable = PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(questions.findByOrganizationId("org-a", pageable))
                .thenReturn(new PageImpl<Question>(List.of(), pageable, 41));

        var page = new QuestionService(questions, access).list("teacher", "org-a", null, pageable);

        assertEquals(2, page.getNumber());
        assertEquals(3, page.getTotalPages());
        verify(access).requireRole("teacher", "org-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        verify(questions).findByOrganizationId("org-a", pageable);
    }

    @Test
    void assessmentSelectorReadsOnlyApprovedQuestionsWithinTenant() {
        var pageable = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(questions.findByOrganizationIdAndStatus("org-b", QuestionStatus.APPROVED, pageable))
                .thenReturn(new PageImpl<Question>(List.of(), pageable, 21));

        var page = new QuestionService(questions, access)
                .list("recruiter", "org-b", QuestionStatus.APPROVED, pageable);

        assertEquals(1, page.getNumber());
        assertEquals(2, page.getTotalPages());
        verify(questions).findByOrganizationIdAndStatus("org-b", QuestionStatus.APPROVED, pageable);
    }
}
