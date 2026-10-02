package com.jachai.organization.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.dto.AddMemberRequest;
import com.jachai.organization.dto.MemberResponse;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MembershipService {
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final TenantAccessService access;

    public MembershipService(MembershipRepository memberships, OrganizationRepository organizations,
                             UserRepository users, TenantAccessService access) {
        this.memberships = memberships;
        this.organizations = organizations;
        this.users = users;
        this.access = access;
    }

    public MemberResponse add(String actorId, String organizationId, AddMemberRequest request) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (!allowedForType(organization.type(), request.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role is unavailable for this organization type");
        }
        User user = users.findByEmail(request.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User must register first"));
        if (!user.emailVerified() || user.disabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_UNAVAILABLE", "User must verify their email first");
        }
        if (memberships.findByUserIdAndOrganizationId(user.id(), organizationId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS", "User already belongs to this organization");
        }
        try {
            memberships.save(new Membership(UUID.randomUUID().toString(), user.id(),
                    organizationId, request.role(), true, Instant.now()));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS", "User already belongs to this organization");
        }
        return new MemberResponse(user.id(), user.email(), user.displayName(), request.role());
    }

    public List<MemberResponse> list(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        List<MemberResponse> result = new ArrayList<>();
        for (Membership membership : memberships.findByOrganizationIdAndActiveTrue(organizationId)) {
            users.findById(membership.userId()).ifPresent(user -> result.add(
                    new MemberResponse(user.id(), user.email(), user.displayName(), membership.role())));
        }
        return result;
    }

    public Page<MemberResponse> listCandidates(String actorId, String organizationId, Pageable pageable) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.TALENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TALENT_ONLY", "Candidates are available in Talent organizations");
        }
        return memberships.findByOrganizationIdAndRoleAndActiveTrue(
                        organizationId, OrganizationRole.CANDIDATE, pageable)
                .map(member -> users.findById(member.userId())
                        .map(user -> new MemberResponse(user.id(), user.email(), user.displayName(), member.role()))
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MEMBER_UNAVAILABLE",
                                "Candidate account is unavailable")));
    }

    private boolean allowedForType(OrganizationType type, OrganizationRole role) {
        if (role == OrganizationRole.ORG_ADMIN) return true;
        return switch (type) {
            case ACADEMY -> role == OrganizationRole.INSTRUCTOR || role == OrganizationRole.LEARNER;
            case TALENT -> role == OrganizationRole.RECRUITER || role == OrganizationRole.CANDIDATE;
        };
    }
}
