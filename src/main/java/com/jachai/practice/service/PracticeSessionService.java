package com.jachai.practice.service;

import com.jachai.practice.model.PracticeSession;
import com.jachai.practice.model.PracticeSession.SessionAnswer;
import com.jachai.practice.repository.PracticeSessionRepository;
import com.jachai.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PracticeSessionService {
    private final PracticeService practice;
    private final PracticeCatalog catalog;
    private final PracticeSessionRepository sessions;
    private final MongoTemplate mongo;

    /** An answered question, with the right answer and why. Only answered questions appear, so nothing leaks early. */
    public record ResultView(String questionId, String selectedOptionId, boolean correct, Instant answeredAt,
                             String correctOptionId, String explanation) { }
    public record SessionView(String id, String pathId, List<PracticeCatalog.QuestionView> questions,
                              List<ResultView> results, boolean complete, int correctCount,
                              Instant createdAt) { }
    public record NextPracticeView(String pathId, String reason, int answered, int correct) { }

    public SessionView start(String userId, String pathId, int questionCount) {
        practice.usage(userId); // The existing Practice account check applies to sessions too.
        List<PracticeCatalog.Question> available = catalog.starterQuestions(pathId);
        int count = "baseline".equals(pathId) ? available.size() : questionCount;
        if (available.isEmpty() || count < 1 || count > available.size()
                || "baseline".equals(pathId) && questionCount != count) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_SESSION",
                    "Choose a listed path and question count");
        }
        Instant now = Instant.now();
        PracticeSession session = new PracticeSession(UUID.randomUUID().toString(), userId, pathId,
                available.subList(0, count).stream().map(PracticeCatalog.Question::id).toList(),
                List.of(), now, now.plus(Duration.ofDays(PracticeLimits.ANSWER_RETENTION_DAYS)));
        return view(sessions.insert(session));
    }

    public SessionView get(String userId, String sessionId) {
        practice.usage(userId);
        return view(requireSession(userId, sessionId));
    }

    public SessionView answer(String userId, String sessionId, String questionId, String optionId) {
        practice.usage(userId);
        PracticeSession session = requireSession(userId, sessionId);
        if (!session.questionIds().contains(questionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ANSWER",
                    "This question is not in the session");
        }
        for (SessionAnswer previous : session.results()) {
            if (previous.questionId().equals(questionId)) {
                if (previous.selectedOptionId().equals(optionId)) return view(session);
                throw new ApiException(HttpStatus.CONFLICT, "PRACTICE_REQUEST_REUSED",
                        "This session question was already answered");
            }
        }
        UUID requestId = UUID.nameUUIDFromBytes(
                (userId + ":" + sessionId + ":" + questionId).getBytes(StandardCharsets.UTF_8));
        PracticeService.Result result = practice.submit(userId, requestId, questionId, optionId);
        Query unanswered = Query.query(Criteria.where("_id").is(sessionId)
                .and("userId").is(userId).and("expiresAt").gt(Instant.now())
                .and("results.questionId").ne(questionId));
        PracticeSession updated = mongo.findAndModify(unanswered,
                new Update().addToSet("results", new SessionAnswer(questionId, optionId,
                        result.correct(), result.answeredAt())),
                FindAndModifyOptions.options().returnNew(true), PracticeSession.class);
        if (updated != null) return view(updated);
        PracticeSession current = requireSession(userId, sessionId);
        for (SessionAnswer previous : current.results()) {
            if (previous.questionId().equals(questionId)) {
                if (previous.selectedOptionId().equals(optionId)) return view(current);
                throw new ApiException(HttpStatus.CONFLICT, "PRACTICE_REQUEST_REUSED",
                        "This session question was already answered");
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "PRACTICE_SESSION_CHANGED",
                "The session changed while saving the answer; reload and try again");
    }

    public NextPracticeView next(String userId) {
        practice.usage(userId);
        Map<String, int[]> totals = new HashMap<>();
        // shortcut: recent 100 retained sessions only; increase with an aggregate if users exceed this window.
        sessions.findByUserId(userId, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "createdAt")))
                .forEach(session -> {
                    if (session.expiresAt().isBefore(Instant.now())
                            || session.results().size() != session.questionIds().size()) return;
                    for (SessionAnswer answer : session.results()) {
                        PracticeCatalog.Question question = catalog.starterQuestions(session.pathId()).stream()
                                .filter(item -> item.id().equals(answer.questionId())).findFirst().orElse(null);
                        if (question == null) continue;
                        String path = question.topic().toLowerCase(java.util.Locale.ROOT);
                        int[] score = totals.computeIfAbsent(path, ignored -> new int[2]);
                        score[0]++;
                        if (answer.correct()) score[1]++;
                    }
                });
        if (totals.isEmpty()) return new NextPracticeView("baseline", "Start with a short skill check", 0, 0);
        String weakest = catalog.paths().stream().map(PracticeCatalog.PathView::id)
                .filter(totals::containsKey)
                .min((left, right) -> {
                    int[] a = totals.get(left), b = totals.get(right);
                    return Long.compare((long) a[1] * b[0], (long) b[1] * a[0]);
                }).orElseThrow();
        int[] score = totals.get(weakest);
        return new NextPracticeView(weakest, "Lowest accuracy in your recent completed sessions",
                score[0], score[1]);
    }

    private PracticeSession requireSession(String userId, String sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .filter(session -> session.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Practice session not found"));
    }

    private SessionView view(PracticeSession session) {
        List<PracticeCatalog.QuestionView> questions = new ArrayList<>();
        for (String questionId : session.questionIds()) {
            catalog.starterQuestions(session.pathId()).stream()
                    .filter(question -> question.id().equals(questionId)).findFirst()
                    .ifPresent(question -> questions.add(question.publicView()));
        }
        List<PracticeCatalog.Question> pool = catalog.starterQuestions(session.pathId());
        List<ResultView> results = session.results().stream().map(answer -> {
            PracticeCatalog.Question question = pool.stream()
                    .filter(item -> item.id().equals(answer.questionId())).findFirst().orElse(null);
            return new ResultView(answer.questionId(), answer.selectedOptionId(), answer.correct(),
                    answer.answeredAt(), question == null ? null : question.correctOptionId(),
                    question == null ? null : question.explanation());
        }).toList();
        return new SessionView(session.id(), session.pathId(), List.copyOf(questions),
                results, session.results().size() == session.questionIds().size(),
                (int) session.results().stream().filter(SessionAnswer::correct).count(), session.createdAt());
    }
}
