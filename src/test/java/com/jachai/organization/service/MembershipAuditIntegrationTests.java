package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MembershipAuditIntegrationTests extends BaseMongoIntegrationTest {
    @Autowired MembershipService service;
    @Autowired MembershipRepository memberships;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void newRoleChangeRepairsMissingPriorEventBeforeReplacingItsMarker() {
        Instant now = Instant.now();
        String orgId = generateTestId("member-audit-org");
        String ownerId = generateTestId("member-audit-owner");
        String learnerId = generateTestId("member-audit-learner");
        String memberId = generateTestId("member-audit-membership");
        String priorChangeId = generateTestId("member-audit-prior-change");
        String priorEventId = "membership:" + priorChangeId;
        trackDocumentId("users", ownerId);
        trackDocumentId("users", learnerId);
        trackDocumentId("organizations", orgId);
        trackDocumentId("memberships", memberId);
        trackDocumentId("audit_events", priorEventId);
        users.save(new User(ownerId, ownerId + "@example.test", "Owner", null,
                true, false, 0, now));
        users.save(new User(learnerId, learnerId + "@example.test", "Learner", null,
                true, false, 0, now));
        organizations.save(new Organization(orgId, "Member audit recovery", OrganizationType.ACADEMY,
                ownerId, now));
        // Model a successful earlier role CAS followed by a failed audit insert.
        memberships.save(new Membership(memberId, learnerId, orgId, OrganizationRole.INSTRUCTOR,
                true, now, priorChangeId, ownerId, now,
                OrganizationRole.LEARNER, true));

        try {
            service.changeRole(ownerId, orgId, learnerId, OrganizationRole.LEARNER);
        } finally {
            Membership latest = memberships.findById(memberId).orElseThrow();
            if (latest.lastChangeId() != null) {
                trackDocumentId("audit_events", "membership:" + latest.lastChangeId());
            }
        }

        assertTrue(auditEvents.existsById(priorEventId));
        assertEquals(OrganizationRole.LEARNER, memberships.findById(memberId).orElseThrow().role());
        var history = service.audit(ownerId, orgId, learnerId, 0);
        assertEquals(2, history.getTotalElements());
        assertTrue(history.getContent().stream().anyMatch(event ->
                event.role() == OrganizationRole.INSTRUCTOR));
        assertTrue(history.getContent().stream().anyMatch(event ->
                event.role() == OrganizationRole.LEARNER));
    }
}
