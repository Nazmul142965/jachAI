package com.jachai.practice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DuplicateKeyException;

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
    void invalidModelOutputDoesNotSaveQuestion() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(ai.generate(any(), any(), any())).thenReturn("not JSON");

        assertThrows(ApiException.class, () -> service().generate("learner",
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "")));
        org.mockito.Mockito.verifyNoInteractions(questions);
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
        org.mockito.Mockito.verifyNoInteractions(ai);
    }

    private PersonalPracticeGenerationService service() {
        return new PersonalPracticeGenerationService(users, questions, mongo, ai, new ObjectMapper(), 100);
    }

    private User verifiedUser() {
        return new User("learner", "learner@example.com", "Learner", "hash", true, false, 0, Instant.now());
    }
}
