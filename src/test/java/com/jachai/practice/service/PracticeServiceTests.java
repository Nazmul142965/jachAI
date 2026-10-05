package com.jachai.practice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.practice.model.PracticeAnswer;
import com.jachai.practice.model.PracticeUsage;
import com.jachai.practice.repository.PracticeAnswerRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class PracticeServiceTests {
    @Mock UserRepository users;
    @Mock PracticeAnswerRepository answers;
    @Mock MongoTemplate mongo;
    PracticeService service;

    @BeforeEach
    void setUp() {
        service = new PracticeService(users, new PracticeCatalog(), answers, mongo);
    }

    @Test
    void publicQuestionsExcludeAnswerKeysAndExplanations() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        var questions = service.questions("learner");
        assertFalse(questions.isEmpty());
        assertEquals("java-record", questions.getFirst().id());
        assertEquals(4, questions.getFirst().getClass().getRecordComponents().length);
    }

    @Test
    void correctAnswerIsStoredOnlyAfterSubmission() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        when(answers.insert(any(PracticeAnswer.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.submit("learner", id, "java-record", "b");
        assertEquals(true, result.correct());
        assertEquals("b", result.correctOptionId());
        verify(mongo).insert(any(PracticeUsage.class));
        verify(answers).insert(any(PracticeAnswer.class));
    }

    @Test
    void replayWithDifferentChoiceDoesNotConsumeQuota() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        when(answers.findById("learner:" + id)).thenReturn(Optional.of(new PracticeAnswer(
                "learner:" + id, "learner", "java-record", "b", "b", true,
                "Explanation", Instant.now(), Instant.now().plusSeconds(1000))));
        assertThrows(ApiException.class, () -> service.submit("learner", id, "java-record", "a"));
        verify(mongo, never()).insert(any(PracticeUsage.class));
    }

    private User verifiedUser() {
        return new User("learner", "learner@example.com", "Learner", "hash", true, false, 0, Instant.now());
    }
}
