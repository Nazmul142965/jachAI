package com.jachai.question.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionDifficulty;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTests {
    @Mock QuestionRepository questions;
    @Mock TenantAccessService access;
    @Mock MongoTemplate mongo;

    @Test
    void shortAnswerDraftHasNoOptionsOrAnswerKey() {
        var input = new CreateQuestionRequest("Java", "Explain records", QuestionType.SHORT_ANSWER,
                List.of(), Set.of());
        when(questions.save(any(Question.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Question saved = new QuestionService(questions, access, mongo).create("teacher", "org-a", input);

        assertEquals(QuestionType.SHORT_ANSWER, saved.type());
        assertEquals(QuestionDifficulty.MEDIUM, saved.difficulty());
        assertEquals(List.of(), saved.options());
        assertEquals(Set.of(), saved.correctOptionIds());
    }

    @Test
    void shortAnswerRejectsHiddenObjectiveAnswerKey() {
        var input = new CreateQuestionRequest("Java", "Explain records", QuestionType.SHORT_ANSWER,
                List.of(new CreateQuestionRequest.OptionInput("A", "Answer")), Set.of("A"));

        assertEquals("INVALID_QUESTION", assertThrows(ApiException.class,
                () -> new QuestionService(questions, access, mongo).create("teacher", "org-a", input)).code());
    }

    @Test
    void instructorCanChooseDifficultyForAnObjectiveDraft() {
        var input = new CreateQuestionRequest("Java", "What is a record?", QuestionType.SINGLE_CHOICE,
                List.of(new CreateQuestionRequest.OptionInput("A", "Data carrier"),
                        new CreateQuestionRequest.OptionInput("B", "Thread")), Set.of("A"),
                QuestionDifficulty.HARD);
        when(questions.save(any(Question.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Question saved = new QuestionService(questions, access, mongo).create("teacher", "org-a", input);

        assertEquals(QuestionDifficulty.HARD, saved.difficulty());
        assertEquals(QuestionDifficulty.HARD, saved.approved().difficulty());
    }

    @Test
    void objectiveQuestionStillRequiresAtLeastTwoOptions() {
        var input = new CreateQuestionRequest("Java", "What is a record?", QuestionType.SINGLE_CHOICE,
                List.of(new CreateQuestionRequest.OptionInput("A", "Data carrier")), Set.of("A"));

        assertEquals("INVALID_QUESTION", assertThrows(ApiException.class,
                () -> new QuestionService(questions, access, mongo).create("teacher", "org-a", input)).code());
    }

    @Test
    void questionBankReadsOnlyRequestedTenantPage() {
        var pageable = PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(questions.findByOrganizationId("org-a", pageable))
                .thenReturn(new PageImpl<Question>(List.of(), pageable, 41));

        var page = new QuestionService(questions, access, mongo).list("teacher", "org-a", null, pageable);

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

        var page = new QuestionService(questions, access, mongo)
                .list("recruiter", "org-b", QuestionStatus.APPROVED, pageable);

        assertEquals(1, page.getNumber());
        assertEquals(2, page.getTotalPages());
        verify(questions).findByOrganizationIdAndStatus("org-b", QuestionStatus.APPROVED, pageable);
    }

    @Test
    void humanCanCorrectDraftBeforeApproval() {
        var draft = new Question("q1", "org-a", "teacher", "Java", "Wrong wording",
                QuestionType.SINGLE_CHOICE, List.of(new Question.Option("A", "Wrong"),
                        new Question.Option("B", "Right")), Set.of("B"), QuestionStatus.DRAFT,
                java.time.Instant.now(), QuestionDifficulty.MEDIUM);
        var input = new CreateQuestionRequest("Java", "Correct wording", QuestionType.SINGLE_CHOICE,
                List.of(new CreateQuestionRequest.OptionInput("A", "Right"),
                        new CreateQuestionRequest.OptionInput("B", "Wrong")), Set.of("A"),
                QuestionDifficulty.HARD);
        when(questions.findByIdAndOrganizationId("q1", "org-a"))
                .thenReturn(java.util.Optional.of(draft));
        when(mongo.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), org.mockito.ArgumentMatchers.eq(Question.class)))
                .thenReturn(draft);

        new QuestionService(questions, access, mongo).updateDraft("teacher", "org-a", "q1", input);

        var query = org.mockito.ArgumentCaptor.forClass(Query.class);
        var update = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(query.capture(), update.capture(),
                any(FindAndModifyOptions.class), org.mockito.ArgumentMatchers.eq(Question.class));
        assertEquals("q1", query.getValue().getQueryObject().get("_id"));
        assertEquals("org-a", query.getValue().getQueryObject().get("organizationId"));
        assertEquals(QuestionStatus.DRAFT, query.getValue().getQueryObject().get("status"));
        assertEquals("Correct wording", ((org.bson.Document) update.getValue().getUpdateObject().get("$set"))
                .get("prompt"));
    }

    @Test
    void approvedQuestionCannotBeEdited() {
        var approved = new Question("q1", "org-a", "teacher", "Java", "Prompt",
                QuestionType.SHORT_ANSWER, List.of(), Set.of(), QuestionStatus.APPROVED,
                java.time.Instant.now(), QuestionDifficulty.MEDIUM);
        when(questions.findByIdAndOrganizationId("q1", "org-a"))
                .thenReturn(java.util.Optional.of(approved));

        ApiException error = assertThrows(ApiException.class, () ->
                new QuestionService(questions, access, mongo).updateDraft("teacher", "org-a", "q1",
                        new CreateQuestionRequest("Java", "New prompt", QuestionType.SHORT_ANSWER,
                                List.of(), Set.of())));

        assertEquals("QUESTION_APPROVED", error.code());
        org.mockito.Mockito.verifyNoInteractions(mongo);
    }
}
