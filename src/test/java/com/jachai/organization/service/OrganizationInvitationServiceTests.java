package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.dto.CreateOrganizationInvitationRequest;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationInvitation;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationInvitationRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationInvitationServiceTests {
    @Mock OrganizationInvitationRepository invitations;
    @Mock OrganizationRepository organizations;
    @Mock MembershipRepository memberships;
    @Mock UserRepository users;
    @Mock MembershipService memberService;
    @Mock TenantAccessService access;
    @Mock EmailSender emailSender;

    private OrganizationInvitationService service() {
        var result = new OrganizationInvitationService(invitations, organizations,
                memberships, users, memberService, access, emailSender);
        ReflectionTestUtils.setField(result, "publicBaseUrl", "https://jachai.example/");
        return result;
    }

    private Organization organization() {
        return new Organization("org", "Acme Academy", OrganizationType.ACADEMY, "owner", Instant.now());
    }

    private User learner() {
        return new User("learner", "learner@example.com", "Learner", null, true, false, 0, Instant.now());
    }

    private OrganizationInvitation invitation(Instant expiry, Instant acceptedAt, Instant revokedAt) {
        return new OrganizationInvitation("invite", "org", "learner@example.com",
                OrganizationRole.LEARNER, "owner", "stored-hash", Instant.now(), expiry,
                acceptedAt, revokedAt, 1L);
    }

    @Test
    void ownerSendsHashedInvitationWithoutExposingToken() throws Exception {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.insert(any(OrganizationInvitation.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().invite("owner", "org",
                new CreateOrganizationInvitationRequest(" LEARNER@example.com ", OrganizationRole.LEARNER));

        assertEquals("learner@example.com", view.email());
        assertFalse(view.accepted());
        ArgumentCaptor<OrganizationInvitation> stored = ArgumentCaptor.forClass(OrganizationInvitation.class);
        verify(invitations).insert(stored.capture());
        assertEquals(64, stored.getValue().tokenHash().length());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOrganizationInvitation(org.mockito.ArgumentMatchers.eq("learner@example.com"),
                org.mockito.ArgumentMatchers.eq("Acme Academy"),
                org.mockito.ArgumentMatchers.eq("LEARNER"), link.capture(), any(Instant.class));
        assertTrue(link.getValue().startsWith("https://jachai.example/?memberInvitation="));
        String token = URI.create(link.getValue()).getRawQuery().substring("memberInvitation=".length());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))), stored.getValue().tokenHash());
        verify(access).requireRole("owner", "org", OrganizationRole.ORG_ADMIN);
    }

    @Test
    void reissueRotatesLinkAndKeepsOriginalCreationTime() {
        OrganizationInvitation prior = invitation(Instant.now().minusSeconds(10), null, null);
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.findByOrganizationIdAndEmail("org", "learner@example.com"))
                .thenReturn(Optional.of(prior));
        when(invitations.save(any(OrganizationInvitation.class)))
                .thenAnswer(call -> call.getArgument(0));

        var view = service().invite("owner", "org",
                new CreateOrganizationInvitationRequest("learner@example.com", OrganizationRole.LEARNER));

        ArgumentCaptor<OrganizationInvitation> saved = ArgumentCaptor.forClass(OrganizationInvitation.class);
        verify(invitations).save(saved.capture());
        assertEquals(prior.id(), saved.getValue().id());
        assertEquals(prior.createdAt(), saved.getValue().createdAt());
        assertNotEquals(prior.tokenHash(), saved.getValue().tokenHash());
        assertTrue(saved.getValue().expiresAt().isAfter(prior.expiresAt()));
        assertEquals(prior.id(), view.id());
    }

    @Test
    void acceptedInvitationCannotBeReissuedBeforeMembershipRepair() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.findByOrganizationIdAndEmail("org", "learner@example.com"))
                .thenReturn(Optional.of(invitation(Instant.now().plusSeconds(3600), Instant.now(), null)));

        assertEquals("INVITATION_ACCEPTED", assertThrows(ApiException.class,
                () -> service().invite("owner", "org",
                        new CreateOrganizationInvitationRequest("learner@example.com", OrganizationRole.LEARNER)))
                .code());
        verify(invitations, never()).save(any(OrganizationInvitation.class));
    }

    @Test
    void nonOwnerCannotReplaceOwnersPendingAdminInvitation() {
        OrganizationInvitation prior = new OrganizationInvitation("invite", "org", "admin@example.com",
                OrganizationRole.ORG_ADMIN, "owner", "old-hash", Instant.now(),
                Instant.now().plusSeconds(3600), null, null, 1L);
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.findByOrganizationIdAndEmail("org", "admin@example.com"))
                .thenReturn(Optional.of(prior));

        ApiException error = assertThrows(ApiException.class, () -> service().invite("other-admin", "org",
                new CreateOrganizationInvitationRequest("admin@example.com", OrganizationRole.LEARNER)));

        assertEquals("OWNER_ONLY", error.code());
        verify(invitations, never()).save(any(OrganizationInvitation.class));
        verify(emailSender, never()).sendOrganizationInvitation(any(), any(), any(), any(), any());
    }

    @Test
    void nonOwnerCannotInviteAnotherAdmin() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));

        ApiException error = assertThrows(ApiException.class, () -> service().invite("admin", "org",
                new CreateOrganizationInvitationRequest("other@example.com", OrganizationRole.ORG_ADMIN)));

        assertEquals("OWNER_ONLY", error.code());
        verify(invitations, never()).insert(any(OrganizationInvitation.class));
    }

    @Test
    void academyCannotInviteTalentOnlyRole() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));

        assertEquals("INVALID_ROLE", assertThrows(ApiException.class, () -> service().invite("owner", "org",
                new CreateOrganizationInvitationRequest("person@example.com", OrganizationRole.RECRUITER))).code());
        verify(invitations, never()).insert(any(OrganizationInvitation.class));
    }

    @Test
    void ownerCannotBeInvitedAsRegularMember() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(new User(
                "owner", "owner@example.com", "Owner", null, true, false, 0, Instant.now())));

        assertEquals("MEMBERSHIP_EXISTS", assertThrows(ApiException.class,
                () -> service().invite("owner", "org",
                        new CreateOrganizationInvitationRequest("owner@example.com", OrganizationRole.LEARNER)))
                .code());
        verify(invitations, never()).insert(any(OrganizationInvitation.class));
    }

    @Test
    void inactiveMemberMustBeReactivatedByAdminNotByInvitation() {
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(learner()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.LEARNER, false, Instant.now())));

        assertEquals("MEMBERSHIP_EXISTS", assertThrows(ApiException.class,
                () -> service().invite("owner", "org",
                        new CreateOrganizationInvitationRequest("learner@example.com", OrganizationRole.LEARNER)))
                .code());
        verify(invitations, never()).insert(any(OrganizationInvitation.class));
    }

    @Test
    void staffListIsScopedToTheirOrganization() {
        var pageable = PageRequest.of(0, 20);
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.findByOrganizationId("org", pageable)).thenReturn(new PageImpl<>(
                java.util.List.of(invitation(Instant.now().plusSeconds(3600), null, null))));

        var page = service().list("owner", "org", pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals("learner@example.com", page.getContent().getFirst().email());
        verify(access).requireRole("owner", "org", OrganizationRole.ORG_ADMIN);
        verify(invitations).findByOrganizationId("org", pageable);
    }

    @Test
    void adminInvitationCanOnlyBeRevokedByCurrentOwner() {
        OrganizationInvitation adminInvite = new OrganizationInvitation("invite", "org", "admin@example.com",
                OrganizationRole.ORG_ADMIN, "owner", "hash", Instant.now(),
                Instant.now().plusSeconds(3600), null, null, 1L);
        when(invitations.findByIdAndOrganizationId("invite", "org"))
                .thenReturn(Optional.of(adminInvite));
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));

        assertEquals("OWNER_ONLY", assertThrows(ApiException.class,
                () -> service().revoke("other-admin", "org", "invite")).code());
        verify(invitations, never()).save(any());
    }

    @Test
    void pendingInvitationCanBeRevokedWithoutDeletingMembershipData() {
        when(invitations.findByIdAndOrganizationId("invite", "org"))
                .thenReturn(Optional.of(invitation(Instant.now().plusSeconds(3600), null, null)));
        when(invitations.save(any(OrganizationInvitation.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().revoke("owner", "org", "invite");

        assertTrue(view.revoked());
        verify(invitations).save(any(OrganizationInvitation.class));
        verify(memberService, never()).acceptInvitation(any(), any());
    }

    @Test
    void acceptedMembershipIsCreatedOnlyForMatchingVerifiedAccount() {
        OrganizationInvitation pending = invitation(Instant.now().plusSeconds(3600), null, null);
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(pending));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.save(any(OrganizationInvitation.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().accept("learner", "raw-link-token");

        assertTrue(view.accepted());
        assertTrue(view.expiresAt().isAfter(Instant.now().plusSeconds(6 * 86400)),
                "Accepted invitations remain available for membership repair retries");
        ArgumentCaptor<OrganizationInvitation> accepted = ArgumentCaptor.forClass(OrganizationInvitation.class);
        verify(memberService).acceptInvitation(accepted.capture(), any(User.class));
        assertEquals("learner@example.com", accepted.getValue().email());
        assertEquals(OrganizationRole.LEARNER, accepted.getValue().role());
        assertTrue(accepted.getValue().acceptedAt() != null);
    }

    @Test
    void differentAccountCannotAcceptInvitation() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(
                invitation(Instant.now().plusSeconds(3600), null, null)));
        when(users.findById("other")).thenReturn(Optional.of(new User("other", "other@example.com",
                "Other", null, true, false, 0, Instant.now())));

        ApiException error = assertThrows(ApiException.class, () -> service().accept("other", "raw-link-token"));

        assertEquals("INVITATION_ACCOUNT_MISMATCH", error.code());
        verify(invitations, never()).save(any());
        verify(memberService, never()).acceptInvitation(any(), any());
    }

    @Test
    void existingDifferentRoleDoesNotConsumeInvitation() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(
                invitation(Instant.now().plusSeconds(3600), null, null)));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.INSTRUCTOR, true, Instant.now())));

        assertEquals("MEMBERSHIP_CONFLICT", assertThrows(ApiException.class,
                () -> service().accept("learner", "raw-link-token")).code());
        verify(invitations, never()).save(any(OrganizationInvitation.class));
        verify(memberService, never()).acceptInvitation(any(), any());
    }

    @Test
    void deactivatedMemberCannotConsumeAnOldPendingInvitation() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(
                invitation(Instant.now().plusSeconds(3600), null, null)));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));
        when(memberships.findByUserIdAndOrganizationId("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.LEARNER, false, Instant.now())));

        assertEquals("MEMBERSHIP_CONFLICT", assertThrows(ApiException.class,
                () -> service().accept("learner", "raw-link-token")).code());
        verify(invitations, never()).save(any(OrganizationInvitation.class));
        verify(memberService, never()).acceptInvitation(any(), any());
    }

    @Test
    void revokedOrExpiredLinkCannotCreateMembership() {
        when(invitations.findByTokenHash(any()))
                .thenReturn(Optional.of(invitation(Instant.now().minusSeconds(1), null, null)))
                .thenReturn(Optional.of(invitation(Instant.now().plusSeconds(3600), null, Instant.now())))
                .thenReturn(Optional.of(invitation(Instant.now().minusSeconds(1), Instant.now().minusSeconds(86400), null)));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));

        assertEquals("INVALID_INVITATION", assertThrows(ApiException.class,
                () -> service().accept("learner", "expired")).code());
        assertEquals("INVALID_INVITATION", assertThrows(ApiException.class,
                () -> service().accept("learner", "revoked")).code());
        assertEquals("INVALID_INVITATION", assertThrows(ApiException.class,
                () -> service().accept("learner", "accepted-and-expired")).code());
        verify(memberService, never()).acceptInvitation(any(), any());
    }

    @Test
    void acceptedRetryRunsMembershipRepairWithoutRecheckingInviterRole() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(
                invitation(Instant.now().plusSeconds(3600), Instant.now(), null)));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));
        assertTrue(service().accept("learner", "raw-link-token").accepted());

        verify(memberService).acceptInvitation(any(OrganizationInvitation.class), any(User.class));
    }

    @Test
    void concurrentReissueCannotActivateStaleToken() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(
                invitation(Instant.now().plusSeconds(3600), null, null)));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));
        when(organizations.findById("org")).thenReturn(Optional.of(organization()));
        when(invitations.save(any(OrganizationInvitation.class)))
                .thenThrow(new OptimisticLockingFailureException("rotated"));

        assertEquals("INVITATION_CONFLICT", assertThrows(ApiException.class,
                () -> service().accept("learner", "old-token")).code());
        verify(memberService, never()).acceptInvitation(any(), any());
    }
}
