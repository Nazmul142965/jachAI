package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.dto.CreateOrganizationRequest;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.shared.error.ApiException;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTests {
    @Mock UserRepository users;
    @Mock OrganizationRepository organizations;
    @Mock MembershipRepository memberships;
    @Mock MongoTemplate mongo;
    @Mock AuditEventRepository auditEvents;
    @Mock TenantAccessService access;

    private OrganizationService service() {
        return new OrganizationService(users, organizations, memberships, mongo, auditEvents, access);
    }

    private Organization organization() {
        return new Organization("org", "Academy", OrganizationType.ACADEMY, "owner", Instant.now());
    }

    private void eligibleTarget() {
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("admin", "org"))
                .thenReturn(Optional.of(new Membership("member", "admin", "org",
                        OrganizationRole.ORG_ADMIN, true, Instant.now())));
        when(users.findById("admin")).thenReturn(Optional.of(new User("admin", "admin@example.com",
                "Admin", null, true, false, 0, Instant.now())));
    }

    @Test
    void ambiguousOwnerMembershipWriteKeepsTheWorkspaceDiscoverableAndRepairable() {
        Organization created = organization();
        when(users.findById("owner")).thenReturn(Optional.of(new User("owner", "owner@example.com",
                "Owner", "hash", true, false, 0, Instant.now())));
        when(organizations.save(any(Organization.class))).thenReturn(created);
        doThrow(new IllegalStateException("write acknowledgement lost"))
                .when(memberships).save(any(Membership.class));

        assertEquals("WORKSPACE_CREATION_UNCERTAIN", assertThrows(ApiException.class,
                () -> service().create("owner", new CreateOrganizationRequest("Academy", OrganizationType.ACADEMY)))
                .code());
        verify(organizations, never()).deleteById("org");

        when(organizations.findByOwnerUserId("owner")).thenReturn(List.of(created));
        assertEquals("org", service().listMine("owner").getFirst().id());
        ArgumentCaptor<Membership> repair = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).insert(repair.capture());
        assertEquals("owner", repair.getValue().userId());
        assertEquals(OrganizationRole.ORG_ADMIN, repair.getValue().role());
    }

    @Test
    void onlyCurrentOwnerCanTransfer() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));

        assertEquals("OWNER_ONLY", assertThrows(ApiException.class,
                () -> service().transferOwner("other-admin", "org", "admin")).code());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Organization.class));
    }

    @Test
    void targetMustBeActiveAdmin() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));

        assertEquals("TARGET_NOT_ADMIN", assertThrows(ApiException.class,
                () -> service().transferOwner("owner", "org", "learner")).code());
    }

    @Test
    void transferUsesOwnerCompareAndSwapAndRecordsAudit() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        eligibleTarget();
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Organization.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        service().transferOwner("owner", "org", "admin");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), any(Update.class), eq(Organization.class));
        assertEquals("org", query.getValue().getQueryObject().getString("_id"));
        assertEquals("owner", query.getValue().getQueryObject().getString("ownerUserId"));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(event.capture());
        assertEquals(AuditAction.OWNERSHIP_TRANSFERRED, event.getValue().action());
        assertEquals("owner", event.getValue().details().get("previousOwnerUserId"));
        assertEquals("admin", event.getValue().details().get("ownerUserId"));
    }

    @Test
    void concurrentTransferIsRejected() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        eligibleTarget();
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Organization.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        assertEquals("OWNER_CHANGED", assertThrows(ApiException.class,
                () -> service().transferOwner("owner", "org", "admin")).code());
        verify(auditEvents, never()).insert(any(AuditEvent.class));
    }

    @Test
    void unresolvedPreviousTransferAuditBlocksAnotherOwnershipChange() {
        Instant now = Instant.now();
        when(organizations.findById("org")).thenReturn(Optional.of(new Organization("org", "Academy",
                OrganizationType.ACADEMY, "owner", now, "prior-transfer", "former-owner", "former-owner", now)));
        eligibleTarget();
        doThrow(new IllegalStateException("audit store unavailable"))
                .when(auditEvents).insert(any(AuditEvent.class));

        assertThrows(IllegalStateException.class, () -> service().transferOwner("owner", "org", "admin"));

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Organization.class));
    }

    @Test
    void newOwnerStillSeesWorkspaceIfMembershipWasDeactivatedConcurrently() {
        when(organizations.findByOwnerUserId("admin"))
                .thenReturn(List.of(new Organization("org", "Academy", OrganizationType.ACADEMY,
                        "admin", Instant.now())));

        var result = service().listMine("admin");

        assertEquals(1, result.size());
        assertEquals(OrganizationRole.ORG_ADMIN, result.getFirst().myRole());
        assertEquals(true, result.getFirst().owner());
    }

    @Test
    void historyReadRepairsLatestTransferAudit() {
        Instant now = Instant.now();
        Organization transferred = new Organization("org", "Academy", OrganizationType.ACADEMY,
                "admin", now, "change-1", "owner", "owner", now);
        var page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
        when(organizations.findById("org")).thenReturn(Optional.of(transferred));
        when(auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                "org", "ORGANIZATION", "org", page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));

        service().ownershipAudit("admin", "org", 0);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(event.capture());
        assertEquals("ownership:change-1", event.getValue().id());
        verify(access).requireRole("admin", "org", OrganizationRole.ORG_ADMIN);
    }
}
