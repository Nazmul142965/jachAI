package com.jachai.organization.service;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.jachai.billing.integration.BaseMongoIntegrationTest;
import com.jachai.organization.dto.AuditGapStatus;
import com.jachai.organization.dto.AuditRecoveryReport;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves against a real MongoDB the three behaviours the unit tests can only assume, because they
 * are properties of the driver and the collection rather than of this code:
 *
 * <ul>
 *   <li>A second insert of the same deterministic id raises a duplicate key, which is what makes
 *       repair idempotent rather than duplicating history.</li>
 *   <li>Repair really does write a readable, correctly dated event.</li>
 *   <li>Paging a tenant walks each membership exactly once.</li>
 * </ul>
 *
 * Skipped unless MONGODB_TEST_URI names a dedicated {@code jachai_it_} database.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class AuditRecoveryMongoIntegrationTests extends BaseMongoIntegrationTest {

    @Autowired OrganizationAuditRecoveryService service;
    @Autowired MembershipRepository memberships;
    @Autowired OrganizationRepository organizations;
    @Autowired AuditEventRepository auditEvents;
    // Platform-admin resolution has its own tests; this class is about recovery behaviour.
    @MockitoBean PlatformAdminService platform;

    private String admin() {
        String adminId = generateTestId("audit-recovery-admin");
        when(platform.isPlatformAdmin(adminId)).thenReturn(true);
        return adminId;
    }

    private String organization(String transferId, String previousOwner, Instant transferredAt) {
        String orgId = generateTestId("audit-recovery-org");
        trackDocumentId("organizations", orgId);
        organizations.save(new Organization(orgId, "Audit Recovery Org", OrganizationType.ACADEMY,
                "u-owner", Instant.now(), transferId, previousOwner,
                previousOwner, transferredAt));
        return orgId;
    }

    private String membership(String orgId, String suffix, String changeId,
                              OrganizationRole previousRole, Boolean previousActive) {
        String membershipId = generateTestId("audit-recovery-member-" + suffix);
        String userId = generateTestId("audit-recovery-user-" + suffix);
        trackDocumentId("memberships", membershipId);
        memberships.save(new Membership(membershipId, userId, orgId, OrganizationRole.INSTRUCTOR,
                true, Instant.now(), changeId, "u-actor", Instant.now(), previousRole, previousActive));
        return userId;
    }

    @Test
    void repairWritesTheMissingEventAndASecondRunChangesNothing() {
        String adminId = admin();
        String orgId = organization(null, null, null);
        String changeId = generateTestId("audit-recovery-change");
        String eventId = "membership:" + changeId;
        trackDocumentId("audit_events", eventId);
        String userId = membership(orgId, "a", changeId, OrganizationRole.LEARNER, true);

        AuditRecoveryReport dryRun = service.report(adminId, orgId, 0);
        assertEquals(1, dryRun.recoverableCount());
        assertFalse(dryRun.repaired());
        assertFalse(auditEvents.existsById(eventId), "a dry run must not write");

        AuditRecoveryReport repaired = service.repair(adminId, orgId, 0);
        assertEquals(1, repaired.recoverableCount());
        assertTrue(repaired.repaired());

        AuditEvent written = auditEvents.findById(eventId).orElseThrow();
        assertEquals(orgId, written.organizationId());
        assertEquals("u-actor", written.actorUserId(), "the actor is the one the record recorded");
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, written.action());
        assertEquals(userId, written.targetId());
        assertEquals("AUDIT_RECOVERY", written.details().get("recoveredBy"));

        // The second run is the property the whole design rests on: the deterministic id turns a
        // repeat into a duplicate key rather than a second history entry.
        AuditRecoveryReport again = service.repair(adminId, orgId, 0);
        assertTrue(again.clean(), "a repaired tenant has nothing left to recover");
        assertEquals(1, auditEvents.findAllById(java.util.List.of(eventId)).size());
    }

    @Test
    void repairIsIdempotentEvenWhenTheEventAppearsBetweenTheScanAndTheWrite() {
        String adminId = admin();
        String orgId = organization(null, null, null);
        String changeId = generateTestId("audit-recovery-race");
        String eventId = "membership:" + changeId;
        trackDocumentId("audit_events", eventId);
        String userId = membership(orgId, "race", changeId, OrganizationRole.LEARNER, true);

        // Stand in for the owning service's own replay winning the race.
        auditEvents.insert(new AuditEvent(eventId, orgId, "u-actor",
                AuditAction.MEMBERSHIP_ROLE_CHANGED, "MEMBERSHIP", userId, Instant.now(),
                Map.of("role", OrganizationRole.INSTRUCTOR.name())));

        AuditRecoveryReport report = service.repair(adminId, orgId, 0);

        assertTrue(report.clean(), "an event already present is not a gap");
        AuditEvent surviving = auditEvents.findById(eventId).orElseThrow();
        assertFalse(surviving.details().containsKey("recoveredBy"),
                "the pre-existing event must not be overwritten by the repair");
    }

    @Test
    void ownershipTransferIsRecoveredFromTheOrganizationsOwnMarkers() {
        String adminId = admin();
        String transferId = generateTestId("audit-recovery-transfer");
        String eventId = "ownership:" + transferId;
        trackDocumentId("audit_events", eventId);
        Instant transferredAt = Instant.now().minusSeconds(3600);
        String orgId = organization(transferId, "u-previous-owner", transferredAt);

        service.repair(adminId, orgId, 0);

        AuditEvent written = auditEvents.findById(eventId).orElseThrow();
        assertEquals(AuditAction.OWNERSHIP_TRANSFERRED, written.action());
        assertEquals("u-previous-owner", written.details().get("previousOwnerUserId"));
        assertEquals(transferredAt.toEpochMilli(), written.occurredAt().toEpochMilli(),
                "the event is dated when the transfer happened, not when it was recovered");
    }

    @Test
    void ambiguousRecordsAreReportedAndNeverWritten() {
        String adminId = admin();
        String orgId = organization(null, null, null);
        String changeId = generateTestId("audit-recovery-ambiguous");
        // previousActive is absent, so the transition cannot be named.
        membership(orgId, "ambiguous", changeId, OrganizationRole.LEARNER, null);

        AuditRecoveryReport report = service.repair(adminId, orgId, 0);

        assertEquals(0, report.recoverableCount());
        assertEquals(1, report.ambiguousCount());
        assertEquals(AuditGapStatus.AMBIGUOUS, report.entries().getFirst().status());
        assertFalse(auditEvents.existsById("membership:" + changeId),
                "repair must never write a transition it cannot derive");
    }

    @Test
    void pagingWalksEveryMembershipExactlyOnce() {
        String adminId = admin();
        String orgId = organization(null, null, null);
        int total = OrganizationAuditRecoveryService.PAGE_SIZE + 3;
        for (int index = 0; index < total; index++) {
            String changeId = generateTestId("audit-recovery-page-change-" + index);
            trackDocumentId("audit_events", "membership:" + changeId);
            membership(orgId, "page-" + index, changeId, OrganizationRole.LEARNER, true);
        }

        AuditRecoveryReport first = service.report(adminId, orgId, 0);
        AuditRecoveryReport second = service.report(adminId, orgId, 1);

        assertEquals(total, first.membershipsScanned());
        assertTrue(first.morePages(), "a tenant beyond one page must not look fully scanned");
        assertEquals(OrganizationAuditRecoveryService.PAGE_SIZE, first.recoverableCount());
        assertEquals(3, second.recoverableCount());
        assertFalse(second.ownershipScanned(), "ownership belongs to page zero only");

        java.util.Set<String> ids = new java.util.HashSet<>();
        first.entries().forEach(entry -> ids.add(entry.expectedEventId()));
        second.entries().forEach(entry -> ids.add(entry.expectedEventId()));
        assertEquals(total, ids.size(), "no membership may be seen twice or skipped across pages");
    }
}
