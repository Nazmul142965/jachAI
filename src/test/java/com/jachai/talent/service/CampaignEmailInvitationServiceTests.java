package com.jachai.talent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.talent.dto.CreateEmailInvitationRequest;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import com.jachai.talent.repository.HiringCampaignRepository;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CampaignEmailInvitationServiceTests {
    @Mock CampaignEmailInvitationRepository invitations;
    @Mock HiringCampaignRepository campaigns;
    @Mock OrganizationRepository organizations;
    @Mock MembershipRepository memberships;
    @Mock AssignmentRepository assignments;
    @Mock UserRepository users;
    @Mock TenantAccessService access;
    @Mock EmailSender emailSender;

    private CampaignEmailInvitationService service() {
        var result = new CampaignEmailInvitationService(invitations, campaigns, organizations,
                memberships, assignments, users, access, emailSender);
        ReflectionTestUtils.setField(result, "publicBaseUrl", "https://jachai.example");
        return result;
    }

    private HiringCampaign campaign(boolean open) {
        return new HiringCampaign("campaign", "org", "assessment", "assessment:2",
                "Backend Engineer", "recruiter", open, Instant.now(), 1L);
    }

    private CampaignEmailInvitation invitation(Instant expiry, Instant accepted) {
        return new CampaignEmailInvitation("invite", "org", "campaign", "candidate@example.com",
                "assessment", "assessment:2", 2, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"), "hash", Instant.now(), expiry, accepted, 1L);
    }

    private Assignment assignment() {
        return new Assignment(UUID.nameUUIDFromBytes("campaign:candidate".getBytes(StandardCharsets.UTF_8)).toString(),
                "org", "assessment", "assessment:2",
                "campaign", null, "candidate", 2, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"), Instant.now());
    }

    @Test
    void recruiterCreatesHashedInvitationAndSendsLinkWithoutReturningToken() {
        when(organizations.findById("org")).thenReturn(Optional.of(
                new Organization("org", "Acme", OrganizationType.TALENT, "owner", Instant.now())));
        when(campaigns.findByIdAndOrganizationId("campaign", "org"))
                .thenReturn(Optional.of(campaign(true)));
        when(invitations.insert(any(CampaignEmailInvitation.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().invite("recruiter", "org", "campaign",
                new CreateEmailInvitationRequest(" CANDIDATE@example.com ", 2,
                        Instant.now(), Instant.now().plusSeconds(3600)));

        assertEquals("candidate@example.com", view.email());
        assertFalse(view.accepted());
        ArgumentCaptor<CampaignEmailInvitation> saved = ArgumentCaptor.forClass(CampaignEmailInvitation.class);
        verify(invitations).insert(saved.capture());
        assertEquals(64, saved.getValue().tokenHash().length());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendAssessmentInvitation(org.mockito.ArgumentMatchers.eq("candidate@example.com"),
                org.mockito.ArgumentMatchers.eq("Acme"), org.mockito.ArgumentMatchers.eq("Backend Engineer"),
                link.capture());
        assertTrue(link.getValue().startsWith("https://jachai.example/?invitation="));
        assertFalse(link.getValue().contains(saved.getValue().tokenHash()));
        verify(access).requireRole("recruiter", "org", OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
    }

    @Test
    void closedCampaignCannotInvite() {
        when(organizations.findById("org")).thenReturn(Optional.of(
                new Organization("org", "Acme", OrganizationType.TALENT, "owner", Instant.now())));
        when(campaigns.findByIdAndOrganizationId("campaign", "org"))
                .thenReturn(Optional.of(campaign(false)));
        ApiException error = assertThrows(ApiException.class, () -> service().invite("recruiter", "org", "campaign",
                new CreateEmailInvitationRequest("candidate@example.com", 1,
                        Instant.now(), Instant.now().plusSeconds(3600))));
        assertEquals("CAMPAIGN_CLOSED", error.code());
        verify(emailSender, never()).sendAssessmentInvitation(any(), any(), any(), any());
    }

    @Test
    void retryRotatesPendingLinkSoFailedEmailCanBeResent() {
        when(organizations.findById("org")).thenReturn(Optional.of(
                new Organization("org", "Acme", OrganizationType.TALENT, "owner", Instant.now())));
        when(campaigns.findByIdAndOrganizationId("campaign", "org"))
                .thenReturn(Optional.of(campaign(true)));
        when(invitations.findByOrganizationIdAndCampaignIdAndEmail("org", "campaign", "candidate@example.com"))
                .thenReturn(Optional.of(invitation(Instant.now().plusSeconds(3600), null)));
        when(invitations.save(any(CampaignEmailInvitation.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().invite("recruiter", "org", "campaign",
                new CreateEmailInvitationRequest("candidate@example.com", 2,
                        Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")));

        assertEquals("invite", view.invitationId());
        ArgumentCaptor<CampaignEmailInvitation> rotated = ArgumentCaptor.forClass(CampaignEmailInvitation.class);
        verify(invitations).save(rotated.capture());
        assertFalse(rotated.getValue().tokenHash().equals("hash"));
        verify(invitations, never()).insert(any(CampaignEmailInvitation.class));
        verify(emailSender).sendAssessmentInvitation(any(), any(), any(), any());
    }

    @Test
    void candidateMustUseMatchingVerifiedEmail() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(invitation(Instant.now().plusSeconds(3600), null)));
        when(users.findById("candidate")).thenReturn(Optional.of(new User("candidate", "wrong@example.com", "Wrong",
                "hash", true, false, 0, Instant.now())));
        ApiException error = assertThrows(ApiException.class,
                () -> service().accept("candidate", "raw-token"));
        assertEquals("INVITATION_ACCOUNT_MISMATCH", error.code());
        verify(memberships, never()).insert(any(Membership.class));
    }

    @Test
    void acceptanceCreatesCandidateMembershipAndFrozenAssignment() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(invitation(Instant.now().plusSeconds(3600), null)));
        when(users.findById("candidate")).thenReturn(Optional.of(new User("candidate", "candidate@example.com",
                "Candidate", "hash", true, false, 0, Instant.now())));
        when(memberships.insert(any(Membership.class))).thenAnswer(call -> call.getArgument(0));
        when(assignments.insert(any(Assignment.class))).thenAnswer(call -> call.getArgument(0));
        when(invitations.save(any(CampaignEmailInvitation.class))).thenAnswer(call -> call.getArgument(0));

        Assignment result = service().accept("candidate", "raw-token");

        assertEquals("candidate", result.assignedUserId());
        assertEquals("assessment:2", result.assessmentVersionId());
        ArgumentCaptor<Membership> member = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).insert(member.capture());
        assertEquals(OrganizationRole.CANDIDATE, member.getValue().role());
        ArgumentCaptor<CampaignEmailInvitation> updated = ArgumentCaptor.forClass(CampaignEmailInvitation.class);
        verify(invitations).save(updated.capture());
        assertTrue(updated.getValue().acceptedAt() != null);
    }

    @Test
    void expiredInvitationCannotCreateMembership() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(invitation(Instant.now().minusSeconds(1), null)));
        when(users.findById("candidate")).thenReturn(Optional.of(new User("candidate", "candidate@example.com",
                "Candidate", "hash", true, false, 0, Instant.now())));
        ApiException error = assertThrows(ApiException.class,
                () -> service().accept("candidate", "raw-token"));
        assertEquals("INVALID_INVITATION", error.code());
        verify(memberships, never()).insert(any(Membership.class));
    }

    @Test
    void acceptedInvitationReturnsExistingAssignmentWithoutNewWrites() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(
                invitation(Instant.now().minusSeconds(1), Instant.now().minusSeconds(10))));
        when(users.findById("candidate")).thenReturn(Optional.of(new User("candidate", "candidate@example.com",
                "Candidate", "hash", true, false, 0, Instant.now())));
        when(assignments.findByIdAndOrganizationId(any(), org.mockito.ArgumentMatchers.eq("org")))
                .thenReturn(Optional.of(assignment()));

        Assignment result = service().accept("candidate", "raw-token");

        assertEquals("candidate", result.assignedUserId());
        verify(memberships, never()).insert(any(Membership.class));
        verify(assignments, never()).insert(any(Assignment.class));
    }
}
