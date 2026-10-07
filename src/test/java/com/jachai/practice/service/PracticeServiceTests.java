package com.jachai.practice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.practice.model.PracticeAnswer;
import com.jachai.practice.model.PracticeUsage;
import com.jachai.practice.repository.PracticeAnswerRepository;
import com.jachai.practice.repository.PersonalPracticeQuestionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PracticeServiceTests {
    @Mock UserRepository users;
    @Mock PracticeAnswerRepository answers;
    @Mock PersonalPracticeQuestionRepository personalQuestions;
    @Mock MongoTemplate mongo;
    PracticeService service;

    @BeforeEach
    void setUp() {
        service = new PracticeService(users, new PracticeCatalog(personalQuestions), answers, mongo);
    }

    @Test
    void publicQuestionsExcludeAnswerKeysAndExplanations() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        when(personalQuestions.findByUserId(org.mockito.ArgumentMatchers.eq("learner"), any(Pageable.class)))
                .thenReturn(Page.empty());
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
    void replayWorksAfterGeneratedQuestionExpires() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        PracticeAnswer persisted = new PracticeAnswer(
                "learner:" + id, "learner", "pg-expired", "a", "a", true,
                "Records are transparent data carriers.", Instant.now(), Instant.now().plusSeconds(3600));
        when(answers.findById("learner:" + id)).thenReturn(Optional.of(persisted));

        // The generated question has expired — catalog.find would return null —
        // but replay must succeed from the stored answer document.
        var result = service.submit("learner", id, "pg-expired", "a");

        assertEquals(true, result.correct());
        assertEquals("a", result.correctOptionId());
        verify(personalQuestions, never()).findByIdAndUserId(any(), any());
        verify(mongo, never()).insert(any(PracticeUsage.class));
        verify(mongo, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PracticeUsage.class));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PracticeUsage.class));
    }

    @Test
    void insertErrorDoesNotRefundWhenAnswerWasPersisted() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        String answerId = "learner:" + id;
        PracticeAnswer persistedFromReRead = new PracticeAnswer(
                answerId, "learner", "java-record", "b", "b", true,
                "Records generate component accessors and a canonical constructor.",
                Instant.now(), Instant.now().plusSeconds(3600));
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        when(answers.findById(answerId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persistedFromReRead));
        RuntimeException insertError = new IllegalStateException("network blip after insert");
        when(answers.insert(any(PracticeAnswer.class))).thenThrow(insertError);

        var result = service.submit("learner", id, "java-record", "b");

        assertEquals(true, result.correct());
        verify(mongo, never()).updateFirst(any(), any(), eq(PracticeUsage.class));
    }

    @Test
    void insertErrorFailsClosedWhenReReadCannotEstablishOutcome() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        String answerId = "learner:" + id;
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        RuntimeException readFailure = new IllegalStateException("secondary read timeout");
        when(answers.findById(answerId))
                .thenReturn(Optional.empty())
                .thenThrow(readFailure);
        RuntimeException insertError = new IllegalStateException("primary insert timeout");
        when(answers.insert(any(PracticeAnswer.class))).thenThrow(insertError);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.submit("learner", id, "java-record", "b"));

        assertEquals(insertError, thrown);
        assertTrue(thrown.getSuppressed().length > 0);
        verify(mongo, never()).updateFirst(any(), any(), eq(PracticeUsage.class));
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

    @Test
    void sameRequestIdReturnsSameResultWithoutConsumingQuota() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        PracticeAnswer persisted = new PracticeAnswer(
                "learner:" + id, "learner", "java-record", "b", "b", true,
                "Records generate component accessors and a canonical constructor.",
                Instant.now(), Instant.now().plusSeconds(3600));
        when(answers.findById("learner:" + id)).thenReturn(Optional.of(persisted));

        var first = service.submit("learner", id, "java-record", "b");
        var second = service.submit("learner", id, "java-record", "b");

        assertEquals(first, second);
        verify(mongo, never()).insert(any(PracticeUsage.class));
        verify(mongo, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PracticeUsage.class));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PracticeUsage.class));
    }

    @Test
    void quotaExhaustedThrows429WithoutSavingAnswer() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        String bucketId = "learner:" + day;
        // Bucket exists but is full: findAndModify returns null (query requires used < limit).
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PracticeUsage.class))).thenReturn(null);
        // Concurrent insert also fails because the bucket already exists at the limit.
        when(mongo.insert(any(PracticeUsage.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("bucket exists"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.submit("learner", UUID.randomUUID(), "java-record", "b"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
        assertEquals("PRACTICE_LIMIT_REACHED", error.code());
        verify(answers, never()).insert(any(PracticeAnswer.class));
    }

    @Test
    void unexpectedInsertFailureRefundsQuotaBucket() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        UUID id = UUID.randomUUID();
        when(mongo.insert(any(PracticeUsage.class))).thenAnswer(call -> call.getArgument(0));
        RuntimeException dbError = new IllegalStateException("disk failure");
        when(answers.insert(any(PracticeAnswer.class))).thenThrow(dbError);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.submit("learner", id, "java-record", "b"));

        assertEquals(dbError, thrown);
        // The usage bucket was reserved then refunded on failure.
        verify(mongo, times(1)).updateFirst(any(Query.class), any(Update.class), eq(PracticeUsage.class));
    }

    @Test
    void usageReportsStatedRetentionAndCurrentAllowance() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String bucketId = "learner:" + day;
        when(mongo.findById(bucketId, PracticeUsage.class))
                .thenReturn(new PracticeUsage(bucketId, "learner", day, 7, 3,
                        Instant.now().plusSeconds(86400)));

        var usage = service.usage("learner");

        assertEquals(day, usage.utcDay());
        assertEquals(7, usage.used());
        assertEquals(13, usage.remaining());
        assertEquals(3, usage.generated());
        assertEquals(17, usage.generationRemaining());
        assertEquals(90, usage.answerRetentionDays());
        assertEquals(30, usage.generationRetentionDays());
    }

    @Test
    void usageWithNoBucketReportsFullAllowanceAndRetention() {
        when(users.findById("learner")).thenReturn(Optional.of(verifiedUser()));
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        when(mongo.findById(eq("learner:" + day), eq(PracticeUsage.class))).thenReturn(null);

        var usage = service.usage("learner");

        assertEquals(PracticeLimits.DAILY_FREE_ANSWERS, usage.limit());
        assertEquals(PracticeLimits.DAILY_FREE_GENERATIONS, usage.generationLimit());
        assertEquals(0, usage.used());
        assertEquals(0, usage.generated());
        assertEquals(90, usage.answerRetentionDays());
        assertEquals(30, usage.generationRetentionDays());
    }

    private User verifiedUser() {
        return new User("learner", "learner@example.com", "Learner", "hash", true, false, 0, Instant.now());
    }
}
