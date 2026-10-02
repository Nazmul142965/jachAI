package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.jachai.organization.model.Membership;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationType;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantAccessServiceTests {
    @Mock MembershipRepository memberships;
    @Mock OrganizationRepository organizations;

    @Test
    void deniesCrossOrganizationAccess() {
        TenantAccessService service = new TenantAccessService(memberships, organizations);
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireMember("user-a", "organization-b"));
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void membershipDoesNotGrantUnrelatedRole() {
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("user-a", "academy-a"))
                .thenReturn(Optional.of(new Membership("m1", "user-a", "academy-a",
                        OrganizationRole.LEARNER, true, Instant.now())));
        TenantAccessService service = new TenantAccessService(memberships, organizations);
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireRole("user-a", "academy-a", OrganizationRole.INSTRUCTOR));
        assertEquals("FORBIDDEN", error.code());
    }

    @Test
    void currentOwnerRetainsAdminAccessIfMembershipWasChangedConcurrently() {
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("new-owner", "org"))
                .thenReturn(Optional.of(new Membership("m", "new-owner", "org",
                        OrganizationRole.LEARNER, true, Instant.now())));
        when(organizations.findById("org")).thenReturn(Optional.of(new Organization("org", "Academy",
                OrganizationType.ACADEMY, "new-owner", Instant.now())));

        Membership granted = new TenantAccessService(memberships, organizations)
                .requireRole("new-owner", "org", OrganizationRole.ORG_ADMIN);

        assertEquals(OrganizationRole.ORG_ADMIN, granted.role());
    }

    @Test
    void currentOwnerCanAccessOrganizationEvenIfMembershipIsInactive() {
        when(organizations.findById("org")).thenReturn(Optional.of(new Organization("org", "Academy",
                OrganizationType.ACADEMY, "new-owner", Instant.now())));

        Membership granted = new TenantAccessService(memberships, organizations)
                .requireMember("new-owner", "org");

        assertEquals(OrganizationRole.ORG_ADMIN, granted.role());
        assertEquals(true, granted.active());
    }
}
