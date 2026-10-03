package com.jachai.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.ai.dto.DraftQuestionRequest;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.dto.CreateQuestionRequest;
import com.jachai.question.model.QuestionType;
import com.jachai.question.service.QuestionService;
import com.jachai.shared.error.ApiException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuestionDraftServiceTests {
    @Mock TenantAccessService access;
    @Mock AiQuotaService quota;
    @Mock AiService ai;
    @Mock QuestionService questions;

    private QuestionDraftService service() {
        return new QuestionDraftService(access, quota, ai, questions,
                new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Test
    void generatedQuestionGoesThroughNormalDraftCreation() {
        when(ai.generate(eq(AiUseCase.QUESTION_DRAFT), any(), any())).thenReturn("""
                {"prompt":"What is a record?","options":[
                {"id":"A","text":"Immutable data carrier"},
                {"id":"B","text":"Database table"}],"correctOptionIds":["A"]}
                """);

        service().draft("instructor", "org-a",
                new DraftQuestionRequest("Java", "records", QuestionType.SINGLE_CHOICE));

        ArgumentCaptor<CreateQuestionRequest> captured = ArgumentCaptor.forClass(CreateQuestionRequest.class);
        verify(questions).create(eq("instructor"), eq("org-a"), captured.capture());
        assertEquals("Java", captured.getValue().topic());
        assertEquals("What is a record?", captured.getValue().prompt());
    }

    @Test
    void malformedModelOutputCannotCreateQuestion() {
        when(ai.generate(eq(AiUseCase.QUESTION_DRAFT), any(), any()))
                .thenReturn("not JSON");

        ApiException error = assertThrows(ApiException.class, () -> service().draft("instructor", "org-a",
                new DraftQuestionRequest("Java", "records", QuestionType.SINGLE_CHOICE)));

        assertEquals("AI_INVALID_OUTPUT", error.code());
        verify(questions, never()).create(any(), any(), any());
    }

    @Test
    void shortAnswerCannotUseObjectiveQuestionAiDraftPrompt() {
        ApiException error = assertThrows(ApiException.class, () -> service().draft("instructor", "org-a",
                new DraftQuestionRequest("Java", "Explain records", QuestionType.SHORT_ANSWER)));

        assertEquals("AI_QUESTION_TYPE_UNSUPPORTED", error.code());
        verify(quota, never()).consume(any(), any());
        verify(ai, never()).generate(any(), any(), any());
    }
}
