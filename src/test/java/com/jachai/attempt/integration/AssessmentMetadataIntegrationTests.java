package com.jachai.attempt.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class AssessmentMetadataIntegrationTests extends BaseMongoIntegrationTest {

    @Test
    void batchMetadataLookupNeverReturnsAnotherTenantsPublishedVersion() {
        var mongo = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, databaseName));
        var versions = new MongoRepositoryFactory(mongo).getRepository(AssessmentVersionRepository.class);
        String ownOrg = generateTestId("org");
        String otherOrg = generateTestId("org");
        String ownId = generateTestId("version");
        String otherId = generateTestId("version");
        trackDocumentId("assessment_versions", ownId);
        trackDocumentId("assessment_versions", otherId);
        mongo.insert(version(ownId, ownOrg, "Own assessment"));
        mongo.insert(version(otherId, otherOrg, "Foreign assessment"));

        var ownResults = versions.findByIdInAndOrganizationId(Set.of(ownId, otherId), ownOrg);
        var otherResults = versions.findByIdInAndOrganizationId(Set.of(ownId, otherId), otherOrg);

        assertEquals(List.of(ownId), ownResults.stream().map(AssessmentVersion::id).toList());
        assertEquals(List.of(otherId), otherResults.stream().map(AssessmentVersion::id).toList());
        assertEquals("No external references.", ownResults.getFirst().instructions());
    }

    private static AssessmentVersion version(String id, String organizationId, String title) {
        return new AssessmentVersion(id, organizationId, id, 1, title, 10, 60,
                ResultReleasePolicy.IMMEDIATE, List.of(), Instant.now(), "No external references.");
    }
}
