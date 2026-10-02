package com.jachai.organization.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.dto.CreateOrganizationRequest;
import com.jachai.organization.dto.OrganizationResponse;
import com.jachai.organization.dto.OwnershipAuditView;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.dao.DuplicateKeyException;

@Service
@RequiredArgsConstructor
public class OrganizationService {
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final MongoTemplate mongo;
    private final AuditEventRepository auditEvents;
    private final TenantAccessService access;

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
                OrganizationRole.ORG_ADMIN, true);
    }

    public List<OrganizationResponse> listMine(String userId) {
        Map<String, OrganizationResponse> result = new LinkedHashMap<>();
        for (Membership membership : memberships.findByUserIdAndActiveTrue(userId)) {
            organizations.findById(membership.organizationId()).ifPresent(organization ->
                    result.put(organization.id(), new OrganizationResponse(organization.id(), organization.name(),
                            organization.type(), organization.ownerUserId().equals(userId)
                            ? OrganizationRole.ORG_ADMIN : membership.role(),
                            organization.ownerUserId().equals(userId))));
        }
        for (Organization organization : organizations.findByOwnerUserId(userId)) {
            result.put(organization.id(), new OrganizationResponse(organization.id(), organization.name(),
                    organization.type(), OrganizationRole.ORG_ADMIN, true));
        }
        return new ArrayList<>(result.values());
    }

    public void transferOwner(String actorId, String organizationId, String targetUserId) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (!organization.ownerUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the current owner can transfer ownership");
        }
        if (actorId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_OWNER", "Choose another administrator");
        }
        memberships.findByUserIdAndOrganizationIdAndActiveTrue(targetUserId, organizationId)
                .filter(member -> member.role() == OrganizationRole.ORG_ADMIN)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "TARGET_NOT_ADMIN",
                        "New owner must be an active organization admin"));
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Target account not found"));
        if (!target.emailVerified() || target.disabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_UNAVAILABLE", "New owner account is unavailable");
        }
        Instant now = Instant.now();
        String transferId = UUID.randomUUID().toString();
        Query query = Query.query(Criteria.where("_id").is(organizationId)
                .and("ownerUserId").is(actorId));
        Update update = new Update().set("ownerUserId", targetUserId)
                .set("ownershipTransferId", transferId)
                .set("previousOwnerUserId", actorId)
                .set("ownershipTransferredBy", actorId)
                .set("ownershipTransferredAt", now);
        if (mongo.updateFirst(query, update, Organization.class).getModifiedCount() != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_CHANGED", "Ownership changed concurrently; reload");
        }
        ensureOwnershipAudit(new Organization(organization.id(), organization.name(), organization.type(),
                targetUserId, organization.createdAt(), transferId, actorId, actorId, now));
    }

    public Page<OwnershipAuditView> ownershipAudit(String actorId, String organizationId, int page) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        ensureOwnershipAudit(organization);
        return auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                organizationId, "ORGANIZATION", organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .map(OwnershipAuditView::from);
    }

    private void ensureOwnershipAudit(Organization organization) {
        if (organization.ownershipTransferId() == null || organization.previousOwnerUserId() == null
                || organization.ownershipTransferredBy() == null || organization.ownershipTransferredAt() == null) return;
        try {
            auditEvents.insert(new AuditEvent("ownership:" + organization.ownershipTransferId(),
                    organization.id(), organization.ownershipTransferredBy(),
                    AuditAction.OWNERSHIP_TRANSFERRED, "ORGANIZATION", organization.id(),
                    organization.ownershipTransferredAt(), Map.of(
                    "previousOwnerUserId", organization.previousOwnerUserId(),
                    "ownerUserId", organization.ownerUserId())));
        } catch (DuplicateKeyException ignored) {
            // The immutable transfer was already recorded.
        }
    }
}
