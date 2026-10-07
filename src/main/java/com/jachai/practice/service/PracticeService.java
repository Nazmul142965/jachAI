package com.jachai.practice.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.practice.model.PracticeAnswer;
import com.jachai.practice.model.PracticeUsage;
import com.jachai.practice.repository.PracticeAnswerRepository;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
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
public class PracticeService {
    public static final int DAILY_FREE_LIMIT = PracticeLimits.DAILY_FREE_ANSWERS;
    private final UserRepository users;
    private final PracticeCatalog catalog;
    private final PracticeAnswerRepository answers;
    private final MongoTemplate mongo;

    public record Usage(String utcDay, int used, int limit, int remaining,
                        int generated, int generationLimit, int generationRemaining,
                        int answerRetentionDays, int generationRetentionDays) { }
    public record Result(String questionId, String selectedOptionId, String correctOptionId,
                         boolean correct, String explanation, Instant answeredAt) { }

    public List<PracticeCatalog.QuestionView> questions(String userId) {
        requireUser(userId);
        return catalog.questions(userId);
    }

    public Usage usage(String userId) {
        requireUser(userId);
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        PracticeUsage bucket = mongo.findById(userId + ":" + day, PracticeUsage.class);
        int used = bucket == null ? 0 : bucket.used();
        int generated = bucket == null ? 0 : bucket.generated();
        return new Usage(day, used, PracticeLimits.DAILY_FREE_ANSWERS,
                Math.max(0, PracticeLimits.DAILY_FREE_ANSWERS - used),
                generated, PracticeLimits.DAILY_FREE_GENERATIONS,
                Math.max(0, PracticeLimits.DAILY_FREE_GENERATIONS - generated),
                PracticeLimits.ANSWER_RETENTION_DAYS,
                PracticeLimits.GENERATION_RETENTION_DAYS);
    }

    public Page<PracticeAnswer> history(String userId, int page) {
        requireUser(userId);
        return answers.findByUserId(userId, PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "answeredAt")));
    }

    public Result submit(String userId, UUID requestId, String questionId, String optionId) {
        requireUser(userId);
        String answerId = userId + ":" + requestId;
        PracticeAnswer prior = answers.findById(answerId).orElse(null);
        if (prior != null) return replay(prior, questionId, optionId);

        PracticeCatalog.Question question = catalog.find(userId, questionId);
        if (question == null || question.options().stream().noneMatch(option -> option.id().equals(optionId))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRACTICE_ANSWER", "Choose a listed question and option");
        }
        Instant now = Instant.now();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String bucketId = userId + ":" + day;
        reserve(bucketId, userId, day);
        PracticeAnswer answer = new PracticeAnswer(answerId, userId, questionId, optionId,
                question.correctOptionId(), question.correctOptionId().equals(optionId),
                question.explanation(), now, now.plus(Duration.ofDays(PracticeLimits.ANSWER_RETENTION_DAYS)));
        try {
            answers.insert(answer);
            return result(answer);
        } catch (RuntimeException failure) {
            PracticeAnswer existing;
            try {
                existing = answers.findById(answerId).orElse(null);
            } catch (RuntimeException readFailure) {
                failure.addSuppressed(readFailure);
                throw failure;
            }
            if (existing != null) return replay(existing, questionId, optionId);
            refund(bucketId);
            throw failure;
        }
    }

    private void reserve(String bucketId, String userId, LocalDate day) {
        Query available = Query.query(Criteria.where("_id").is(bucketId).and("used").lt(PracticeLimits.DAILY_FREE_ANSWERS));
        PracticeUsage reserved = mongo.findAndModify(available, new Update().inc("used", 1),
                FindAndModifyOptions.options().returnNew(true), PracticeUsage.class);
        if (reserved != null) return;
        try {
            mongo.insert(new PracticeUsage(bucketId, userId, day.toString(), 1,
                    day.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC)));
        } catch (DuplicateKeyException concurrent) {
            reserved = mongo.findAndModify(available, new Update().inc("used", 1),
                    FindAndModifyOptions.options().returnNew(true), PracticeUsage.class);
            if (reserved == null) throw quotaExceeded();
        }
    }

    private void refund(String bucketId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(bucketId).and("used").gt(0)),
                new Update().inc("used", -1), PracticeUsage.class);
    }

    private Result replay(PracticeAnswer answer, String questionId, String optionId) {
        if (!answer.questionId().equals(questionId) || !answer.selectedOptionId().equals(optionId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PRACTICE_REQUEST_REUSED", "Use a new request ID for a different answer");
        }
        return result(answer);
    }

    private Result result(PracticeAnswer answer) {
        return new Result(answer.questionId(), answer.selectedOptionId(), answer.correctOptionId(),
                answer.correct(), answer.explanation(), answer.answeredAt());
    }

    private void requireUser(String userId) {
        User user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"));
        if (!user.emailVerified() || user.disabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "Account is unavailable");
        }
    }

    private ApiException quotaExceeded() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PRACTICE_LIMIT_REACHED",
                "Today's " + PracticeLimits.DAILY_FREE_ANSWERS + " free practice answers are used. Try again tomorrow (UTC).");
    }
}
