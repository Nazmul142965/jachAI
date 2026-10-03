package com.jachai.attempt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptAnswerStore;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

class AttemptAnswerStoreIntegrationTests extends BaseMongoIntegrationTest {
    private Attempt insertAttempt(MongoTemplate mongo, Instant deadline) {
        String id = generateTestId("attempt");
        trackDocumentId("attempts", id);
        return mongo.insert(new Attempt(id, generateTestId("org"), "assignment", "version", "learner", 1,
                Instant.now().minusSeconds(60), deadline, AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, null));
    }

    @Test
    void databaseTimeRejectsExpiredAnswerAndAcceptsOnlyOneCasWrite() {
        MongoTemplate mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, databaseName));
        AttemptAnswerStore store = new AttemptAnswerStore(mongo);
        Attempt expired = insertAttempt(mongo, Instant.now().minusSeconds(60));
        assertFalse(store.saveOptions(expired, "q1", Set.of("a")));
        assertEquals(Map.of(), mongo.findById(expired.id(), Attempt.class).answers());

        Attempt open = insertAttempt(mongo, Instant.now().plusSeconds(600));
        assertTrue(store.saveOptions(open, "q1", Set.of("a")));
        assertFalse(store.saveText(open, "q-text", "stale revision"));
        Attempt saved = mongo.findById(open.id(), Attempt.class);
        assertEquals(Set.of("a"), saved.answers().get("q1"));
        assertEquals(open.revision() + 1, saved.revision());
        assertTrue(store.saveText(saved, "q-text", "valid answer"));
        assertEquals("valid answer", mongo.findById(open.id(), Attempt.class).textAnswers().get("q-text"));
    }

    @Test
    void tenantAndOwnerArePartOfTheAtomicWritePredicate() {
        MongoTemplate mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, databaseName));
        AttemptAnswerStore store = new AttemptAnswerStore(mongo);
        Attempt open = insertAttempt(mongo, Instant.now().plusSeconds(600));
        Attempt foreign = new Attempt(open.id(), "another-org", open.assignmentId(), open.assessmentVersionId(),
                "another-user", open.attemptNumber(), open.startedAt(), open.deadline(), open.status(),
                open.answers(), open.submittedAt(), open.percentage(), open.passed(), open.resultReleasedAt(),
                open.revision());

        assertFalse(store.saveOptions(foreign, "q1", Set.of("a")));
        assertEquals(Map.of(), mongo.findById(open.id(), Attempt.class).answers());
    }
}
