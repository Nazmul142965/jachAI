package com.jachai.ai.service;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class TutorActiveAttemptIntegrationTests extends BaseMongoIntegrationTest {

    @Test
    void activeAttemptCheckUsesTenantUserStatusAndDeadline() {
        MongoTemplate mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, databaseName));
        AttemptRepository attempts = new MongoRepositoryFactory(mongo).getRepository(AttemptRepository.class);
        String org = generateTestId("org");
        String user = generateTestId("user");
        Instant now = Instant.now();
        insert(attempts, org, user, now.minusSeconds(30));
        insert(attempts, generateTestId("foreign-org"), user, now.plusSeconds(60));

        assertFalse(attempts.existsByOrganizationIdAndUserIdAndStatusAndDeadlineAfter(
                org, user, AttemptStatus.IN_PROGRESS, now));

        insert(attempts, org, user, now.plusSeconds(60));
        assertTrue(attempts.existsByOrganizationIdAndUserIdAndStatusAndDeadlineAfter(
                org, user, AttemptStatus.IN_PROGRESS, now));
        assertFalse(attempts.existsByOrganizationIdAndUserIdAndStatusAndDeadlineAfter(
                org, generateTestId("other-user"), AttemptStatus.IN_PROGRESS, now));
    }

    private void insert(AttemptRepository attempts, String org, String user, Instant deadline) {
        String id = generateTestId("attempt");
        trackDocumentId("attempts", id);
        attempts.insert(new Attempt(id, org, generateTestId("assignment"),
                generateTestId("version"), user, 1, deadline.minusSeconds(120), deadline,
                AttemptStatus.IN_PROGRESS, Map.of(), null, null, null, null, null));
    }
}
