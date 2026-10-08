package com.jachai.academy.repository;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jachai.billing.integration.BaseMongoIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class BatchAnalyticsMongoIntegrationTests extends BaseMongoIntegrationTest {

    @Test
    void latestSubmittedAttemptAndTenantBoundariesDriveCohortTotals() {
        String orgId = generateTestId("analytics-org");
        String foreignOrgId = generateTestId("analytics-foreign-org");
        String batchAssessmentId = generateTestId("analytics-batch-assessment");
        String otherBatchAssessmentId = generateTestId("analytics-other-batch");
        String[] assignmentIds = {
                generateTestId("analytics-assignment-1"),
                generateTestId("analytics-assignment-2"),
                generateTestId("analytics-assignment-3"),
                generateTestId("analytics-assignment-4")
        };
        for (String id : assignmentIds) {
            insert("assignments", new Document("_id", id).append("organizationId", orgId)
                    .append("batchAssessmentId", batchAssessmentId));
        }
        insert("assignments", new Document("_id", generateTestId("other-batch-assignment"))
                .append("organizationId", orgId).append("batchAssessmentId", otherBatchAssessmentId));
        insert("assignments", new Document("_id", generateTestId("foreign-assignment"))
                .append("organizationId", foreignOrgId).append("batchAssessmentId", batchAssessmentId));

        insertAttempt(orgId, assignmentIds[0], 1, 20, false);
        insertAttempt(orgId, assignmentIds[0], 2, 80, true);
        // This foreign-tenant attempt must not make assignment 2 look submitted.
        insertAttempt(foreignOrgId, assignmentIds[1], 1, 100, true);
        insertAttempt(orgId, assignmentIds[2], 1, 100, true);
        insertAttempt(orgId, assignmentIds[2], 2, null, null);
        insertAttempt(orgId, assignmentIds[3], 1, 0, false);

        BatchAnalyticsReader reader = new BatchAnalyticsReader(new MongoTemplate(mongoClient, databaseName));
        BatchAnalyticsReader.Totals totals = reader.totals(orgId, batchAssessmentId);
        assertEquals(4, totals.assigned());
        assertEquals(3, totals.submitted());
        assertEquals(2, totals.graded());
        assertEquals(1, totals.passed());
        assertEquals(40.0, totals.average());

        BatchAnalyticsReader.Totals missing = reader.totals(orgId, generateTestId("no-such-batch"));
        assertEquals(0, missing.assigned());
        assertEquals(0, missing.submitted());
        assertNull(missing.average());
    }

    private void insertAttempt(String organizationId, String assignmentId, int attemptNumber,
                               Integer percentage, Boolean passed) {
        Document attempt = new Document("_id", generateTestId("analytics-attempt"))
                .append("organizationId", organizationId).append("assignmentId", assignmentId)
                .append("attemptNumber", attemptNumber).append("status", "SUBMITTED");
        if (percentage != null) attempt.append("percentage", percentage);
        if (passed != null) attempt.append("passed", passed);
        insert("attempts", attempt);
    }

    private void insert(String collection, Document document) {
        trackDocumentId(collection, document.getString("_id"));
        testDatabase.getCollection(collection).insertOne(document);
    }
}
