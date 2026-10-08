package com.jachai.organization.service;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class OrganizationOwnershipIntegrationTests extends BaseMongoIntegrationTest {
    @Autowired OrganizationService service;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired UserRepository users;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void concurrentTransfersChooseExactlyOneOwnerAndAuditItOnce() throws Exception {
        Instant now = Instant.now();
        String orgId = generateTestId("ownership-race-org");
        String originalOwner = generateTestId("race-owner");
        String firstAdmin = generateTestId("race-admin-one");
        String secondAdmin = generateTestId("race-admin-two");
        for (String userId : new String[] {originalOwner, firstAdmin, secondAdmin}) {
            trackDocumentId("users", userId);
            users.save(new User(userId, userId + "@example.test", userId, null, true, false, 0, now));
        }
        trackDocumentId("organizations", orgId);
        organizations.save(new Organization(orgId, "Ownership race", OrganizationType.ACADEMY,
                originalOwner, now));
        for (String adminId : new String[] {firstAdmin, secondAdmin}) {
            String membershipId = generateTestId("race-membership");
            trackDocumentId("memberships", membershipId);
            memberships.save(new Membership(membershipId, adminId, orgId,
                    OrganizationRole.ORG_ADMIN, true, now));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transferWhenReleased(ready, start, originalOwner, orgId, firstAdmin));
            var second = pool.submit(() -> transferWhenReleased(ready, start, originalOwner, orgId, secondAdmin));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both transfers must reach the start barrier");
            start.countDown();
            List<String> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("SUCCESS"::equals).count());
            assertTrue(outcomes.stream().allMatch(value -> List.of("SUCCESS", "OWNER_CHANGED", "OWNER_ONLY").contains(value)));
        }

        Organization latest = organizations.findById(orgId).orElseThrow();
        assertTrue(List.of(firstAdmin, secondAdmin).contains(latest.ownerUserId()));
        String eventId = "ownership:" + latest.ownershipTransferId();
        trackDocumentId("audit_events", eventId);
        assertTrue(auditEvents.existsById(eventId));
        var history = service.ownershipAudit(latest.ownerUserId(), orgId, 0);
        assertEquals(1, history.getTotalElements());
        assertEquals(originalOwner, history.getContent().getFirst().previousOwnerUserId());
        assertEquals(latest.ownerUserId(), history.getContent().getFirst().ownerUserId());
    }

    private String transferWhenReleased(CountDownLatch ready, CountDownLatch start,
                                        String actorId, String organizationId, String targetId) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("transfer start timed out");
        try {
            service.transferOwner(actorId, organizationId, targetId);
            return "SUCCESS";
        } catch (ApiException expectedConflict) {
            return expectedConflict.code();
        }
    }

    @Test
    void secondTransferRepairsTheFirstAuditBeforeReplacingItsRecoveryMarker() {
        Instant now = Instant.now();
        String orgId = generateTestId("ownership-org");
        String originalOwner = generateTestId("owner");
        String firstAdmin = generateTestId("admin-one");
        String secondAdmin = generateTestId("admin-two");
        for (String userId : new String[] {originalOwner, firstAdmin, secondAdmin}) {
            trackDocumentId("users", userId);
            users.save(new User(userId, userId + "@example.test", userId, null, true, false, 0, now));
        }
        trackDocumentId("organizations", orgId);
        organizations.save(new Organization(orgId, "Ownership smoke", OrganizationType.ACADEMY,
                originalOwner, now));
        for (String adminId : new String[] {firstAdmin, secondAdmin}) {
            String membershipId = generateTestId("membership");
            trackDocumentId("memberships", membershipId);
            memberships.save(new Membership(membershipId, adminId, orgId,
                    OrganizationRole.ORG_ADMIN, true, now));
        }

        service.transferOwner(originalOwner, orgId, firstAdmin);
        String firstEventId = "ownership:" + organizations.findById(orgId).orElseThrow().ownershipTransferId();
        trackDocumentId("audit_events", firstEventId);
        assertTrue(auditEvents.existsById(firstEventId));

        // Simulate the recoverable state left by an audit insert failure after the owner CAS.
        auditEvents.deleteById(firstEventId);
        try {
            service.transferOwner(firstAdmin, orgId, secondAdmin);
        } finally {
            Organization latest = organizations.findById(orgId).orElseThrow();
            if (latest.ownershipTransferId() != null) {
                trackDocumentId("audit_events", "ownership:" + latest.ownershipTransferId());
            }
        }

        assertEquals(secondAdmin, organizations.findById(orgId).orElseThrow().ownerUserId());
        assertTrue(auditEvents.existsById(firstEventId));
        var history = service.ownershipAudit(secondAdmin, orgId, 0);
        assertEquals(2, history.getTotalElements());
        assertTrue(history.getContent().stream().anyMatch(event ->
                originalOwner.equals(event.previousOwnerUserId()) && firstAdmin.equals(event.ownerUserId())));
        assertTrue(history.getContent().stream().anyMatch(event ->
                firstAdmin.equals(event.previousOwnerUserId()) && secondAdmin.equals(event.ownerUserId())));
        assertEquals("OWNER_ONLY", assertThrows(ApiException.class,
                () -> service.transferOwner(originalOwner, orgId, firstAdmin)).code());
    }
}
