package com.jachai.practice.integration;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.ai.service.AiService;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.practice.model.PersonalPracticeQuestion;
import com.jachai.practice.model.PracticeAnswer;
import com.jachai.practice.model.PracticeUsage;
import com.jachai.practice.repository.PersonalPracticeQuestionRepository;
import com.jachai.practice.repository.PracticeAnswerRepository;
import com.jachai.practice.service.PersonalPracticeGenerationService;
import com.jachai.practice.service.PracticeCatalog;
import com.jachai.practice.service.PracticeCatalog.Option;
import com.jachai.practice.service.PracticeLimits;
import com.jachai.practice.service.PracticeService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

@EnabledIf("integrationTestsEnabled")
class PracticeIntegrationTests extends BaseMongoIntegrationTest {

    static boolean integrationTestsEnabled() {
        String uri = System.getenv("MONGODB_TEST_URI");
        return uri != null && !uri.isBlank();
    }

    private PracticeService practice;
    private PersonalPracticeGenerationService generation;
    private MongoTemplate mongo;
    private UserRepository users;
    private PracticeAnswerRepository answers;
    private PersonalPracticeQuestionRepository personalQuestions;
    private AiService ai;

    @BeforeEach
    void setupServices() {
        mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, databaseName));
        mongo.indexOps(PracticeAnswer.class).ensureIndex(
                new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
        mongo.indexOps(PracticeUsage.class).ensureIndex(
                new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
        mongo.indexOps(PersonalPracticeQuestion.class).ensureIndex(
                new Index().on("expiresAt", Sort.Direction.ASC).expire(0));

        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongo);
        answers = factory.getRepository(PracticeAnswerRepository.class);
        personalQuestions = factory.getRepository(PersonalPracticeQuestionRepository.class);
        users = mock(UserRepository.class);
        ai = mock(AiService.class);

        practice = new PracticeService(users, new PracticeCatalog(personalQuestions), answers, mongo);
        generation = new PersonalPracticeGenerationService(users, personalQuestions, mongo,
                ai, new ObjectMapper(), 100);
    }

    @Test
    void answerSubmissionPersistsAndReplayIsIdempotent() {
        String userId = generateTestId("learner");
        when(users.findById(userId)).thenReturn(Optional.of(verifiedUser(userId)));

        UUID requestId = UUID.nameUUIDFromBytes((userId + "-a1").getBytes());
        String answerId = userId + ":" + requestId;
        trackDocumentId("practice_answers", answerId);
        trackDocumentId("practice_usage", userId + ":" + LocalDate.now(ZoneOffset.UTC).toString());

        PracticeService.Result first = practice.submit(userId, requestId, "java-record", "b");
        assertTrue(first.correct());
        assertEquals("b", first.selectedOptionId());

        PracticeAnswer persisted = mongo.findById(answerId, PracticeAnswer.class);
        assertNotNull(persisted, "Answer must be persisted in Mongo");

        PracticeService.Result second = practice.submit(userId, requestId, "java-record", "b");
        assertEquals(first.questionId(), second.questionId());
        assertEquals(first.selectedOptionId(), second.selectedOptionId());
        assertTrue(second.correct());

        long count = mongo.count(Query.query(Criteria.where("userId").is(userId)), PracticeAnswer.class);
        assertEquals(1, count, "Replay must not create a duplicate answer document");
    }

    @Test
    void dailyAnswerQuotaEnforcedAtomicallyByMongo() {
        String userId = generateTestId("quota");
        when(users.findById(userId)).thenReturn(Optional.of(verifiedUser(userId)));

        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        trackDocumentId("practice_usage", userId + ":" + day.toString());

        for (int i = 0; i < PracticeLimits.DAILY_FREE_ANSWERS; i++) {
            UUID requestId = UUID.nameUUIDFromBytes((userId + "-seq-" + i).getBytes());
            String answerId = userId + ":" + requestId;
            trackDocumentId("practice_answers", answerId);
            practice.submit(userId, requestId, "java-record", "b");
        }

        ApiException error = assertThrows(ApiException.class,
                () -> practice.submit(userId, UUID.randomUUID(), "java-record", "b"));
        assertEquals("PRACTICE_LIMIT_REACHED", error.code());
        assertEquals(PracticeLimits.DAILY_FREE_ANSWERS,
                mongo.findById(userId + ":" + day, PracticeUsage.class).used());
    }

    @Test
    void generatedQuestionIsAccessibleOnlyToOwner() {
        String ownerId = generateTestId("owner");
        String intruderId = generateTestId("intruder");
        when(users.findById(ownerId)).thenReturn(Optional.of(verifiedUser(ownerId)));
        when(users.findById(intruderId)).thenReturn(Optional.of(verifiedUser(intruderId)));

        Instant now = Instant.now();
        PersonalPracticeQuestion question = new PersonalPracticeQuestion(
                "pg-private", ownerId, "Java", "What is a record?",
                List.of(new Option("a", "A data carrier class"), new Option("b", "A mutable bean")),
                "a", "Records are transparent carriers for immutable data.", now,
                now.plusSeconds(PracticeLimits.GENERATION_RETENTION_DAYS * 24L * 60 * 60));
        mongo.insert(question);
        trackDocumentId("personal_practice_questions", "pg-private");
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        trackDocumentId("practice_usage", ownerId + ":" + day.toString());
        trackDocumentId("practice_usage", intruderId + ":" + day.toString());

        UUID requestId = UUID.randomUUID();
        String answerId = ownerId + ":" + requestId;
        trackDocumentId("practice_answers", answerId);
        practice.submit(ownerId, requestId, "pg-private", "a");

        ApiException error = assertThrows(ApiException.class,
                () -> practice.submit(intruderId, UUID.randomUUID(), "pg-private", "a"));
        assertEquals("INVALID_PRACTICE_ANSWER", error.code());

        long intruderAnswers = mongo.count(
                Query.query(Criteria.where("userId").is(intruderId)), PracticeAnswer.class);
        assertEquals(0, intruderAnswers, "Intruder must not have any answers");
    }

    @Test
    void usageReadsRealMongoBucketState() {
        String userId = generateTestId("usage");
        when(users.findById(userId)).thenReturn(Optional.of(verifiedUser(userId)));
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        trackDocumentId("practice_usage", userId + ":" + day.toString());

        PracticeService.Usage initial = practice.usage(userId);
        assertEquals(0, initial.used());
        assertEquals(0, initial.generated());
        assertEquals(PracticeLimits.DAILY_FREE_ANSWERS, initial.limit());
        assertEquals(90, initial.answerRetentionDays());
        assertEquals(30, initial.generationRetentionDays());

        UUID requestId = UUID.randomUUID();
        String answerId = userId + ":" + requestId;
        trackDocumentId("practice_answers", answerId);
        practice.submit(userId, requestId, "java-record", "b");

        PracticeService.Usage after = practice.usage(userId);
        assertEquals(1, after.used());
        assertEquals(PracticeLimits.DAILY_FREE_ANSWERS - 1, after.remaining());
    }

    @Test
    void generationReservesQuotaAtomicallyAndPersistsQuestion() {
        String userId = generateTestId("generator");
        when(users.findById(userId)).thenReturn(Optional.of(verifiedUser(userId)));
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        trackDocumentId("practice_usage", userId + ":" + day.toString());
        String globalBucketId = "personal-ai:global:" + day.toString();
        mongo.remove(Query.query(Criteria.where("_id").is(globalBucketId)), PracticeUsage.class);
        trackDocumentId("practice_usage", globalBucketId);
        when(ai.generate(any(), any(), any())).thenReturn("""
                {"prompt":"What is a Java record?","options":[
                  {"id":"a","text":"An immutable data carrier"},{"id":"b","text":"A mutable class"},
                  {"id":"c","text":"An annotation"},{"id":"d","text":"A lambda"}],
                 "correctOptionId":"a","explanation":"Records carry immutable data."}
                """);

        PracticeCatalog.QuestionView view = generation.generate(userId,
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "records"));

        assertNotNull(view.id());
        assertNotNull(view.topic());
        assertEquals(4, view.options().size());

        PersonalPracticeQuestion saved = mongo.findById(view.id(), PersonalPracticeQuestion.class);
        assertNotNull(saved, "Generated question must be persisted in Mongo");
        assertEquals(userId, saved.userId(), "Question must belong to the requesting user only");
        trackDocumentId("personal_practice_questions", view.id());

        PracticeUsage bucket = mongo.findById(userId + ":" + day, PracticeUsage.class);
        assertNotNull(bucket);
        assertEquals(1, bucket.generated());

        PracticeUsage globalBucket = mongo.findById("personal-ai:global:" + day, PracticeUsage.class);
        assertNotNull(globalBucket);
        assertEquals(1, globalBucket.generated());
    }

    @Test
    void generationFailureUnreservesBothBuckets() {
        String userId = generateTestId("failgen");
        when(users.findById(userId)).thenReturn(Optional.of(verifiedUser(userId)));
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        String dayStr = day.toString();
        trackDocumentId("practice_usage", userId + ":" + dayStr);
        String globalBucketId = "personal-ai:global:" + dayStr;
        mongo.remove(Query.query(Criteria.where("_id").is(globalBucketId)), PracticeUsage.class);
        trackDocumentId("practice_usage", globalBucketId);

        when(ai.generate(any(), any(), any())).thenThrow(
                new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "AI_PROVIDER_UNAVAILABLE", "provider timed out"));

        assertThrows(ApiException.class, () -> generation.generate(userId,
                new PersonalPracticeGenerationService.GenerateRequest("Java", "BEGINNER", "")));

        PracticeUsage bucket = mongo.findById(userId + ":" + dayStr, PracticeUsage.class);
        assertNotNull(bucket);
        assertEquals(0, bucket.generated(), "User generation bucket must be refunded on AI failure");
        PracticeUsage globalBucket = mongo.findById("personal-ai:global:" + dayStr, PracticeUsage.class);
        assertNotNull(globalBucket);
        assertEquals(0, globalBucket.generated(), "Global generation bucket must be refunded on AI failure");
    }

    private User verifiedUser(String id) {
        return new User(id, id + "@example.test", "Learner " + id, "hash", true, false, 0, Instant.now());
    }
}
