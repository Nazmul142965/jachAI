package com.jachai.organization.service;

import com.jachai.organization.model.Membership;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.shared.error.ApiException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TenantAccessService {
    private final MembershipRepository memberships;

    public TenantAccessService(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public Membership requireMember(String userId, String organizationId) {
        return memberships.findByUserIdAndOrganizationIdAndActiveTrue(userId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
    }

    public Membership requireRole(String userId, String organizationId, OrganizationRole... allowed) {
        Membership membership = requireMember(userId, organizationId);
        if (Arrays.stream(allowed).noneMatch(role -> role == membership.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient organization permission");
        }
        return membership;
    }
}
