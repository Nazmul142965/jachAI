package com.jachai.practice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.practice.model.PracticeSession;
import com.jachai.practice.model.PracticeSession.SessionAnswer;
import com.jachai.practice.repository.PersonalPracticeQuestionRepository;
import com.jachai.practice.repository.PracticeSessionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class PracticeSessionServiceTests {
    private final PracticeService practice = mock(PracticeService.class);
    private final PracticeCatalog catalog = new PracticeCatalog(mock(PersonalPracticeQuestionRepository.class));
    private final PracticeSessionRepository sessions = mock(PracticeSessionRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final PracticeSessionService service = new PracticeSessionService(practice, catalog, sessions, mongo);

    @Test
    void baselineHasOneQuestionPerPathAndNoAnswerKey() {
        when(sessions.insert(any(PracticeSession.class))).thenAnswer(call -> call.getArgument(0));

        var view = service.start("learner", "baseline", 4);

        assertEquals(4, view.questions().size());
        assertEquals(4, view.questions().stream().map(PracticeCatalog.QuestionView::topic).distinct().count());
        assertEquals(4, view.questions().getFirst().getClass().getRecordComponents().length);
        assertFalse(view.complete());
        assertThrows(ApiException.class, () -> service.start("learner", "baseline", 3));
    }

    @Test
    void sessionAnswerUsesExistingQuotaAndReturnsRecapAfterLastQuestion() {
        PracticeSession session = session("java", List.of("java-record"), List.of());
        PracticeSession completed = session("java", List.of("java-record"),
                List.of(new SessionAnswer("java-record", "b", true, Instant.now())));
        when(sessions.findByIdAndUserId("session", "learner")).thenReturn(java.util.Optional.of(session));
        when(practice.submit(eq("learner"), any(), eq("java-record"), eq("b")))
                .thenReturn(new PracticeService.Result("java-record", "b", "b", true, "Correct", Instant.now()));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(PracticeSession.class))).thenReturn(completed);

        var recap = service.answer("learner", "session", "java-record", "b");

        assertTrue(recap.complete());
        assertEquals(1, recap.correctCount());
        assertEquals("b", recap.results().get(0).correctOptionId());
        assertTrue(!recap.results().get(0).explanation().isBlank());
        verify(practice).submit(eq("learner"), any(), eq("java-record"), eq("b"));
    }

    @Test
    void nextPathUsesWeakestCompletedTopicNotAnIncompleteSession() {
        PracticeSession java = session("java", List.of("java-record", "java-optional"),
                List.of(new SessionAnswer("java-record", "a", false, Instant.now()),
                        new SessionAnswer("java-optional", "b", false, Instant.now())));
        PracticeSession web = session("web", List.of("http-idempotent"),
                List.of(new SessionAnswer("http-idempotent", "c", true, Instant.now())));
        PracticeSession incomplete = session("spring", List.of("spring-transaction", "spring-component"),
                List.of(new SessionAnswer("spring-transaction", "b", true, Instant.now())));
        when(sessions.findByUserId(eq("learner"), any()))
                .thenReturn(new PageImpl<>(List.of(java, web, incomplete)));

        var next = service.next("learner");

        assertEquals("java", next.pathId());
        assertEquals(2, next.answered());
        assertEquals(0, next.correct());
    }

    private static PracticeSession session(String path, List<String> questions, List<SessionAnswer> results) {
        Instant now = Instant.now();
        return new PracticeSession("session", "learner", path, questions, results,
                now, now.plusSeconds(3600));
    }
}
