package com.jachai.organization.service;

import com.jachai.organization.model.Membership;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TenantAccessService {
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;

    public TenantAccessService(MembershipRepository memberships, OrganizationRepository organizations) {
        this.memberships = memberships;
        this.organizations = organizations;
    }

    public Membership requireMember(String userId, String organizationId) {
        return memberships.findByUserIdAndOrganizationIdAndActiveTrue(userId, organizationId)
                .orElseGet(() -> ownerMembership(userId, organizationId));
    }

    public Membership requireRole(String userId, String organizationId, OrganizationRole... allowed) {
        Membership membership = requireMember(userId, organizationId);
        if (Arrays.stream(allowed).noneMatch(role -> role == membership.role())) {
            if (Arrays.asList(allowed).contains(OrganizationRole.ORG_ADMIN)) {
                return organizations.findById(organizationId)
                        .filter(organization -> organization.ownerUserId().equals(userId))
                        .map(organization -> ownerMembership(userId, organizationId))
                        .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "Insufficient organization permission"));
            }
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient organization permission");
        }
        return membership;
    }

    private Membership ownerMembership(String userId, String organizationId) {
        return organizations.findById(organizationId)
                .filter(organization -> organization.ownerUserId().equals(userId))
                .map(organization -> new Membership("owner:" + organizationId, userId,
                        organizationId, OrganizationRole.ORG_ADMIN, true, organization.createdAt()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
    }
}
