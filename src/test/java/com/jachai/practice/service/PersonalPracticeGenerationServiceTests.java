package com.jachai.practice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.ai.service.AiService;
import com.jachai.ai.service.AiUseCase;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.practice.model.PersonalPracticeQuestion;
import com.jachai.practice.model.PracticeUsage;
import com.jachai.practice.repository.PersonalPracticeQuestionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PersonalPracticeGenerationServiceTests {
    @Mock UserRepository users;
    @Mock PersonalPracticeQuestionRepository questions;
    @Mock MongoTemplate mongo;
    @Mock AiService ai;

    @Test
    void generatedQuestionIsStoredPrivatelyAndReturnedWithoutAnswerKey() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(questions.insert(any(PersonalPracticeQuestion.class))).thenAnswer(call -> call.getArgument(0));
        when(ai.generate(any(), any(), any())).thenReturn("""
                {"prompt":"What does a Spring transaction do?","options":[
                  {"id":"a","text":"Groups changes"},{"id":"b","text":"Changes Java syntax"},
                  {"id":"c","text":"Starts a browser"},{"id":"d","text":"Creates a thread"}],
                 "correctOptionId":"a","explanation":"A transaction groups data changes."}
                """);
        var service = service();
        var result = service.generate("learner", new PersonalPracticeGenerationService.GenerateRequest(
                "Spring", "INTERMEDIATE", "transactions"));

        assertEquals("Spring", result.topic());
        assertEquals(4, result.options().size());
        assertEquals(4, result.getClass().getRecordComponents().length);
        verify(ai).generate(org.mockito.ArgumentMatchers.eq(AiUseCase.QUESTION_DRAFT), any(),
                org.mockito.ArgumentMatchers.contains("INTERMEDIATE"));
    }

    @Test
    void invalidModelOutputDoesNotSaveQuestionAndRefundsBothBuckets() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(ai.generate(any(), any(), any())).thenReturn("not JSON");

        ApiException error = assertThrows(ApiException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "")));
        assertEquals("AI_INVALID_OUTPUT", error.code());
        verifyNoInteractions(questions);
        verify(mongo, times(2)).updateFirst(any(Query.class), any(Update.class), eq(PracticeUsage.class));
    }

    @Test
    void globalCapacityIsNotReportedAsPersonalLimit() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> {
            PracticeUsage bucket = call.getArgument(0);
            if (bucket.id().startsWith("personal-ai:global:")) throw new DuplicateKeyException("existing bucket");
            return bucket;
        });
        ApiException error = assertThrows(ApiException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "")));
        assertEquals("PRACTICE_AI_PLATFORM_CAPACITY", error.code());
        verifyNoInteractions(ai);
    }

    @Test
    void providerAuthErrorRefundsBothBucketsAndPropagatesCategory() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(ai.generate(any(), any(), any())).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_AUTH", "provider key rejected"));

        ApiException error = assertThrows(ApiException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "")));

        assertEquals("AI_PROVIDER_AUTH", error.code());
        verifyNoInteractions(questions);
        verify(mongo, times(2)).updateFirst(any(Query.class), any(Update.class), eq(PracticeUsage.class));
    }

    @Test
    void globalCapExhaustionRefundsPersonalBucketAndDoesNotCallAi() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> {
            PracticeUsage bucket = call.getArgument(0);
            if (bucket.id().startsWith("personal-ai:global:")) {
                throw new DuplicateKeyException("global bucket exists");
            }
            return bucket;
        });

        ApiException error = assertThrows(ApiException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "")));

        assertEquals("PRACTICE_AI_PLATFORM_CAPACITY", error.code());
        verifyNoInteractions(ai);
        verifyNoInteractions(questions);
        // The user bucket was reserved then refunded.
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(PracticeUsage.class));
    }

     @Test
    void invalidTopicIsRejectedBeforeAnyQuotaOrAiCall() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        String tooLong = "x".repeat(81);

        ApiException error = assertThrows(ApiException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest(tooLong, "BEGINNER", "")));

        assertEquals("INVALID_PRACTICE_BRIEF", error.code());
        verifyNoInteractions(mongo, ai, questions);
    }

    @Test
    void insertErrorDoesNotRefundWhenQuestionWasPersisted() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(ai.generate(any(), any(), any())).thenReturn("""
                {"prompt":"What is a Java record?","options":[
                  {"id":"a","text":"A data carrier class"},{"id":"b","text":"A mutable bean"},
                  {"id":"c","text":"An annotation"},{"id":"d","text":"A lambda"}],
                 "correctOptionId":"a","explanation":"Records carry immutable data."}
                """);
        String persistedId = "pg-already-saved";
        PersonalPracticeQuestion persisted = new PersonalPracticeQuestion(
                persistedId, "learner", "Java", "What is a Java record?",
                List.of(new PracticeCatalog.Option("a", "A data carrier class")),
                "a", "Records carry immutable data.", Instant.now(),
                Instant.now().plusSeconds(60));
        when(questions.findById(any())).thenReturn(Optional.of(persisted));
        RuntimeException insertError = new IllegalStateException("primary insert timed out");
        when(questions.insert(any(PersonalPracticeQuestion.class))).thenThrow(insertError);

        PracticeCatalog.QuestionView view = service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "records"));

        assertNotNull(view);
        assertEquals(persistedId, view.id());
        verify(mongo, never()).updateFirst(any(), any(), eq(PracticeUsage.class));
    }

    @Test
    void insertErrorFailsClosedWhenReReadCannotEstablishOutcome() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(ai.generate(any(), any(), any())).thenReturn("""
                {"prompt":"What is a Java record?","options":[
                  {"id":"a","text":"A data carrier class"},{"id":"b","text":"A mutable bean"},
                  {"id":"c","text":"An annotation"},{"id":"d","text":"A lambda"}],
                 "correctOptionId":"a","explanation":"Records carry immutable data."}
                """);
        RuntimeException readFailure = new IllegalStateException("secondary read timeout");
        when(questions.findById(any())).thenThrow(readFailure);
        RuntimeException insertError = new IllegalStateException("primary insert timed out");
        when(questions.insert(any(PersonalPracticeQuestion.class))).thenThrow(insertError);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "records")));

        assertEquals(insertError, thrown);
        assertTrue(thrown.getSuppressed().length > 0);
        verify(mongo, never()).updateFirst(any(), any(), eq(PracticeUsage.class));
    }

    private PersonalPracticeGenerationService service() {
        return new PersonalPracticeGenerationService(users, questions, mongo, ai, new ObjectMapper(), 100);
    }

    private User verifiedUser() {
        return new User("learner", "learner@example.com", "Learner", "hash", true, false, 0, Instant.now());
    }
}
