package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTests {
    @Mock MembershipRepository memberships;
    @Mock OrganizationRepository organizations;
    @Mock UserRepository users;
    @Mock TenantAccessService access;

    private MembershipService service() {
        return new MembershipService(memberships, organizations, users, access);
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
}
