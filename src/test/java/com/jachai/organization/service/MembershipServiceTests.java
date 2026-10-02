package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.mongodb.client.result.UpdateResult;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.dto.AddMemberRequest;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.shared.audit.AuditAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTests {
    @Mock MembershipRepository memberships;
    @Mock OrganizationRepository organizations;
    @Mock UserRepository users;
    @Mock TenantAccessService access;
    @Mock MongoTemplate mongo;
    @Mock AuditEventRepository auditEvents;

    private MembershipService service() {
        return new MembershipService(memberships, organizations, users, access, mongo, auditEvents);
    }

    private Organization academy() {
        return new Organization("org-a", "Academy", OrganizationType.ACADEMY, "owner", Instant.now());
    }

    private Membership member(String userId, OrganizationRole role, boolean active) {
        return new Membership("membership-" + userId, userId, "org-a", role, active, Instant.now());
    }

    private void successfulUpdate() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Membership.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));
    }

    @Test
    void recruiterGetsOnlyTenantScopedActiveCandidates() {
        var pageable = PageRequest.of(0, 20);
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Talent org", OrganizationType.TALENT, "owner", Instant.now())));
        when(memberships.findByOrganizationIdAndRoleAndActiveTrue(
                "org-a", OrganizationRole.CANDIDATE, pageable)).thenReturn(new PageImpl<>(List.of(
                new Membership("member", "candidate", "org-a", OrganizationRole.CANDIDATE,
                        true, Instant.now())), pageable, 1));
        when(users.findById("candidate")).thenReturn(Optional.of(new User(
                "candidate", "candidate@example.com", "Candidate", "hash", true, false, 0, Instant.now())));

        var result = service().listCandidates("recruiter", "org-a", pageable);

        assertEquals("candidate", result.getContent().getFirst().userId());
        verify(access).requireRole("recruiter", "org-a", OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        verify(memberships).findByOrganizationIdAndRoleAndActiveTrue(
                "org-a", OrganizationRole.CANDIDATE, pageable);
    }

    @Test
    void recruiterCanSelectCandidatesBeyondFirstPage() {
        var pageable = PageRequest.of(1, 20);
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Talent org", OrganizationType.TALENT, "owner", Instant.now())));
        when(memberships.findByOrganizationIdAndRoleAndActiveTrue(
                "org-a", OrganizationRole.CANDIDATE, pageable)).thenReturn(new PageImpl<>(List.of(
                new Membership("member-21", "candidate-21", "org-a", OrganizationRole.CANDIDATE,
                        true, Instant.now())), pageable, 21));
        when(users.findById("candidate-21")).thenReturn(Optional.of(new User(
                "candidate-21", "candidate21@example.com", "Candidate 21", "hash", true, false, 0, Instant.now())));

        var result = service().listCandidates("recruiter", "org-a", pageable);

        assertEquals(1, result.getNumber());
        assertEquals(2, result.getTotalPages());
        assertEquals("candidate-21", result.getContent().getFirst().userId());
        verify(memberships).findByOrganizationIdAndRoleAndActiveTrue(
                "org-a", OrganizationRole.CANDIDATE, pageable);
    }

    @Test
    void academyCannotReadTalentCandidateDirectory() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Academy", OrganizationType.ACADEMY, "owner", Instant.now())));

        ApiException error = assertThrows(ApiException.class,
                () -> service().listCandidates("teacher", "org-a", PageRequest.of(0, 20)));

        assertEquals("TALENT_ONLY", error.code());
    }

    @Test
    void listIdentifiesOwnerWithoutGrantingItToOtherAdmins() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByOrganizationId("org-a"))
                .thenReturn(List.of(member("owner", OrganizationRole.ORG_ADMIN, true),
                        member("admin", OrganizationRole.ORG_ADMIN, true)));
        when(users.findById("owner")).thenReturn(Optional.of(new User("owner", "owner@example.com",
                "Owner", null, true, false, 0, Instant.now())));
        when(users.findById("admin")).thenReturn(Optional.of(new User("admin", "admin@example.com",
                "Admin", null, true, false, 0, Instant.now())));

        var result = service().list("owner", "org-a");

        assertTrue(result.get(0).owner());
        assertFalse(result.get(1).owner());
        assertTrue(result.get(0).active());
    }

    @Test
    void nonOwnerAdminCannotGrantAdminRole() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));

        ApiException error = assertThrows(ApiException.class, () -> service().add("admin", "org-a",
                new AddMemberRequest("new@example.com", OrganizationRole.ORG_ADMIN)));

        assertEquals("OWNER_ONLY", error.code());
        verify(users, never()).findByEmail(any());
    }

    @Test
    void ownerCannotBeDemotedOrDeactivated() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("owner", "org-a"))
                .thenReturn(Optional.of(member("owner", OrganizationRole.ORG_ADMIN, true)));

        assertEquals("OWNER_PROTECTED", assertThrows(ApiException.class,
                () -> service().changeRole("owner", "org-a", "owner", OrganizationRole.LEARNER)).code());
        assertEquals("OWNER_PROTECTED", assertThrows(ApiException.class,
                () -> service().deactivate("owner", "org-a", "owner")).code());
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Membership.class));
    }

    @Test
    void nonOwnerCannotDemoteAnotherAdmin() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("other-admin", "org-a"))
                .thenReturn(Optional.of(member("other-admin", OrganizationRole.ORG_ADMIN, true)));

        assertEquals("OWNER_ONLY", assertThrows(ApiException.class,
                () -> service().changeRole("admin", "org-a", "other-admin", OrganizationRole.INSTRUCTOR)).code());
    }

    @Test
    void roleChangeIsAtomicAndTenantScoped() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(member("learner", OrganizationRole.LEARNER, true)));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner", "learner@example.com",
                "Learner", null, true, false, 0, Instant.now())));
        successfulUpdate();

        var result = service().changeRole("owner", "org-a", "learner", OrganizationRole.INSTRUCTOR);

        assertEquals(OrganizationRole.INSTRUCTOR, result.role());
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(query.capture(), any(Update.class), eq(Membership.class));
        assertEquals("org-a", query.getValue().getQueryObject().getString("organizationId"));
        assertEquals("learner", query.getValue().getQueryObject().getString("userId"));
        assertEquals("LEARNER", query.getValue().getQueryObject().get("role").toString());
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(audit.capture());
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, audit.getValue().action());
        assertEquals("owner", audit.getValue().actorUserId());
        assertEquals("LEARNER", audit.getValue().details().get("previousRole"));
    }

    @Test
    void inactiveMemberCanBeReactivatedWithoutDuplicateMembership() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, false, 0, Instant.now())));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(member("learner", OrganizationRole.LEARNER, false)));
        successfulUpdate();

        var result = service().add("owner", "org-a",
                new AddMemberRequest("learner@example.com", OrganizationRole.INSTRUCTOR));

        assertEquals(OrganizationRole.INSTRUCTOR, result.role());
        verify(memberships, never()).save(any(Membership.class));
        verify(auditEvents).insert(any(AuditEvent.class));
    }

    @Test
    void deactivationRejectsConcurrentMembershipChange() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(member("learner", OrganizationRole.LEARNER, true)));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Membership.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        assertEquals("MEMBERSHIP_CHANGED", assertThrows(ApiException.class,
                () -> service().deactivate("owner", "org-a", "learner")).code());
    }

    @Test
    void retryRepairsRoleAuditWithoutRepeatingMembershipUpdate() {
        Instant now = Instant.now();
        Membership changed = new Membership("membership-learner", "learner", "org-a",
                OrganizationRole.INSTRUCTOR, true, now, "change-1", "owner", now,
                OrganizationRole.LEARNER, true);
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(changed));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner", "learner@example.com",
                "Learner", null, true, false, 0, now)));

        service().changeRole("owner", "org-a", "learner", OrganizationRole.INSTRUCTOR);

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(Membership.class));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(event.capture());
        assertEquals("membership:change-1", event.getValue().id());
    }

    @Test
    void auditFailureDoesNotUndoMemberRole() {
        Instant now = Instant.now();
        Membership previous = member("learner", OrganizationRole.LEARNER, true);
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(previous));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner", "learner@example.com",
                "Learner", null, true, false, 0, now)));
        successfulUpdate();
        doThrow(new IllegalStateException("audit unavailable")).when(auditEvents).insert(any(AuditEvent.class));

        assertThrows(IllegalStateException.class,
                () -> service().changeRole("owner", "org-a", "learner", OrganizationRole.INSTRUCTOR));

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(event.capture());
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, event.getValue().action());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Membership.class));
    }

    @Test
    void deactivationRecordsActorAndOldRole() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(member("learner", OrganizationRole.LEARNER, true)));
        successfulUpdate();

        service().deactivate("owner", "org-a", "learner");

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(event.capture());
        assertEquals(AuditAction.MEMBERSHIP_DEACTIVATED, event.getValue().action());
        assertEquals("owner", event.getValue().actorUserId());
        assertEquals("LEARNER", event.getValue().details().get("previousRole"));
    }

    @Test
    void memberAuditIsTenantScopedAndPaged() {
        var page = PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(member("learner", OrganizationRole.LEARNER, false)));
        when(auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                "org-a", "MEMBERSHIP", "learner", page))
                .thenReturn(new PageImpl<>(List.of(new AuditEvent("event", "org-a", "owner",
                        AuditAction.MEMBERSHIP_DEACTIVATED, "MEMBERSHIP", "learner", Instant.now(),
                        java.util.Map.of("previousRole", "LEARNER", "role", "LEARNER"))), page, 21));

        var history = service().audit("owner", "org-a", "learner", 1);

        assertEquals(2, history.getTotalPages());
        assertEquals(AuditAction.MEMBERSHIP_DEACTIVATED, history.getContent().getFirst().action());
        verify(access).requireRole("owner", "org-a", OrganizationRole.ORG_ADMIN);
    }

    @Test
    void readingHistoryRepairsLastMissingAuditEvent() {
        Instant now = Instant.now();
        Membership changed = new Membership("membership-learner", "learner", "org-a",
                OrganizationRole.INSTRUCTOR, true, now, "change-1", "owner", now,
                OrganizationRole.LEARNER, true);
        var page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
        when(memberships.findByUserIdAndOrganizationId("learner", "org-a"))
                .thenReturn(Optional.of(changed));
        when(auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                "org-a", "MEMBERSHIP", "learner", page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));

        service().audit("owner", "org-a", "learner", 0);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(event.capture());
        assertEquals("membership:change-1", event.getValue().id());
    }
}
