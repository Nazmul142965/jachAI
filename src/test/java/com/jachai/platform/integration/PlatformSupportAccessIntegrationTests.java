package com.jachai.platform.integration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.platform.dto.AssessmentSupportPreviewRequest;
import com.jachai.platform.model.PlatformSupportAccessEvent;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.platform.service.PlatformSupportAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class PlatformSupportAccessIntegrationTests extends BaseMongoIntegrationTest {
    @Autowired private PlatformSupportAccessService support;
    @Autowired private MongoTemplate mongo;
    @MockitoBean private PlatformAdminService platform;
    @MockitoBean private OrganizationRepository organizations;
    @MockitoBean private TenantAccessService tenantAccess;

    private String organizationId;

    @AfterEach
    void removeOnlyThisTestsAccessEvents() {
        if (organizationId != null) {
            mongo.remove(Query.query(Criteria.where("organizationId").is(organizationId)),
                    PlatformSupportAccessEvent.class);
        }
    }

    @Test
    void realMongoPersistsAuditBeforeMetadataAndRejectsForeignAssessment() {
        organizationId = generateTestId("support-org");
        String foreignOrg = generateTestId("foreign-org");
        String ownId = generateTestId("assessment");
        String foreignId = generateTestId("assessment");
        trackDocumentId("assessments", ownId);
        trackDocumentId("assessments", foreignId);
        mongo.insert(assessment(ownId, organizationId, "Tenant assessment"));
        mongo.insert(assessment(foreignId, foreignOrg, "Foreign assessment"));
        when(platform.isPlatformAdmin("platform-admin")).thenReturn(true);
        when(organizations.existsById(organizationId)).thenReturn(true);

        var preview = support.preview("platform-admin", organizationId,
                new AssessmentSupportPreviewRequest(ownId, "TICKET-1042",
                        "Investigating assessment publication metadata", true));

        assertEquals("Tenant assessment", preview.title());
        assertEquals(2, preview.questionCount());
        assertEquals(1, mongo.count(Query.query(Criteria.where("organizationId").is(organizationId)),
                PlatformSupportAccessEvent.class));
        assertEquals(preview.accessEventId(),
                support.platformHistory("platform-admin", organizationId, 0).getContent().getFirst().eventId());
        assertEquals(preview.accessEventId(),
                support.tenantHistory("tenant-admin", organizationId, 0).getContent().getFirst().eventId());

        ApiException denied = assertThrows(ApiException.class, () -> support.preview("platform-admin",
                organizationId, new AssessmentSupportPreviewRequest(foreignId, "TICKET-1043",
                        "Investigating a foreign assessment metadata request", true)));
        assertEquals("NOT_FOUND", denied.code());
        assertEquals(1, mongo.count(Query.query(Criteria.where("organizationId").is(organizationId)),
                PlatformSupportAccessEvent.class));
        assertTrue(mongo.indexOps(PlatformSupportAccessEvent.class).getIndexInfo().stream()
                .anyMatch(index -> "support_access_org_time".equals(index.getName())));
    }

    private Assessment assessment(String id, String orgId, String title) {
        return new Assessment(id, orgId, "author", title, 30, 60, List.of("q1", "q2"),
                ResultReleasePolicy.MANUAL, AssessmentStatus.PUBLISHED, 1, Instant.now(), null);
    }
}
