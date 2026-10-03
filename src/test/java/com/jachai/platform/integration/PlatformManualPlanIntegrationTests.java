package com.jachai.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.billing.service.EntitlementService;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.model.PlatformPlanAction;
import com.jachai.platform.model.PlatformPlanEvent;
import com.jachai.platform.repository.PlatformPlanEventRepository;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.platform.service.PlatformPlanService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class PlatformManualPlanIntegrationTests extends BaseMongoIntegrationTest {
    @Autowired private PlatformPlanService plans;
    @Autowired private EntitlementService entitlements;
    @Autowired private PlatformPlanEventRepository events;
    @Autowired private MongoTemplate mongo;
    @MockitoBean private PlatformAdminService platform;
    @MockitoBean private OrganizationRepository organizations;
    @MockitoBean private SubscriptionRepository subscriptions;

    private String organizationId;

    @AfterEach
    void removeOnlyThisTestsEvents() {
        if (organizationId != null) {
            mongo.remove(Query.query(Criteria.where("organizationId").is(organizationId)),
                    PlatformPlanEvent.class);
        }
    }

    @Test
    void manualGrantAndRevocationChangeEntitlementsAndPreserveAuditedRevisions() {
        organizationId = generateTestId("manual-plan-org");
        when(platform.isPlatformAdmin("platform-admin")).thenReturn(true);
        when(organizations.existsById(organizationId)).thenReturn(true);
        Instant expiresAt = Instant.now().plusSeconds(3600);

        assertEquals(PlanTier.FREE, entitlements.plan(organizationId).tier());
        var granted = plans.grant("platform-admin", organizationId, expiresAt, "Demo evaluation period");
        assertTrue(granted.active());
        assertEquals(1, granted.revision());
        assertEquals(PlanTier.TEAM, entitlements.plan(organizationId).tier());

        var repeated = plans.grant("platform-admin", organizationId, expiresAt, "Demo evaluation period");
        assertEquals(1, repeated.revision());
        assertEquals(1, eventCount());

        var revoked = plans.revoke("platform-admin", organizationId, "Demo evaluation concluded");
        assertFalse(revoked.active());
        assertEquals(2, revoked.revision());
        assertEquals(PlanTier.FREE, entitlements.plan(organizationId).tier());
        assertEquals(PlatformPlanAction.REVOKED,
                plans.history("platform-admin", organizationId, 0).getContent().getFirst().action());

        var grantedAgain = plans.grant("platform-admin", organizationId,
                expiresAt.plusSeconds(60), "Extended demo evaluation");
        assertEquals(3, grantedAgain.revision());
        assertEquals(3, eventCount());
        assertEquals(PlanTier.TEAM, entitlements.plan(organizationId).tier());

        assertThrows(DuplicateKeyException.class, () -> events.insert(new PlatformPlanEvent(
                UUID.randomUUID().toString(), organizationId, "platform-admin",
                PlatformPlanAction.GRANTED, expiresAt, "Duplicate historical revision", 1,
                Instant.now())));
        assertEquals(3, eventCount());
    }

    private long eventCount() {
        return mongo.count(Query.query(Criteria.where("organizationId").is(organizationId)),
                PlatformPlanEvent.class);
    }
}
