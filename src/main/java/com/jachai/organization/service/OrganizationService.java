package com.jachai.organization.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.dto.CreateOrganizationRequest;
import com.jachai.organization.dto.OrganizationResponse;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrganizationService {
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;

    public OrganizationService(UserRepository users, OrganizationRepository organizations,
                               MembershipRepository memberships) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
    }

    public OrganizationResponse create(String userId, CreateOrganizationRequest request) {
        User owner = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Account not found"));
        if (!owner.emailVerified() || owner.disabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Account cannot create organizations");
        }
        Instant now = Instant.now();
        Organization organization = organizations.save(new Organization(UUID.randomUUID().toString(),
                request.name().trim(), request.type(), userId, now));
        try {
            memberships.save(new Membership(UUID.randomUUID().toString(), userId, organization.id(),
                    OrganizationRole.ORG_ADMIN, true, now));
        } catch (RuntimeException exception) {
            organizations.deleteById(organization.id());
            throw exception;
        }
        return new OrganizationResponse(organization.id(), organization.name(), organization.type(),
                OrganizationRole.ORG_ADMIN);
    }

    public List<OrganizationResponse> listMine(String userId) {
        List<OrganizationResponse> result = new ArrayList<>();
        for (Membership membership : memberships.findByUserIdAndActiveTrue(userId)) {
            organizations.findById(membership.organizationId()).ifPresent(organization ->
                    result.add(new OrganizationResponse(organization.id(), organization.name(),
                            organization.type(), membership.role())));
        }
        return result;
    }
}
