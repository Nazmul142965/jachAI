package com.jachai.organization.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.dto.AddMemberRequest;
import com.jachai.organization.dto.MemberResponse;
import com.jachai.organization.dto.MemberAuditView;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@Service
@RequiredArgsConstructor
public class MembershipService {
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final TenantAccessService access;
    private final MongoTemplate mongo;
    private final AuditEventRepository auditEvents;

    public MemberResponse add(String actorId, String organizationId, AddMemberRequest request) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (!allowedForType(organization.type(), request.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role is unavailable for this organization type");
        }
        requireOwnerForAdminChange(actorId, organization, null, request.role());
        User user = users.findByEmail(request.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User must register first"));
        if (!user.emailVerified() || user.disabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_UNAVAILABLE", "User must verify their email first");
        }
        var existing = memberships.findByUserIdAndOrganizationId(user.id(), organizationId);
        if (existing.isPresent()) {
            ensureAudit(existing.get());
            if (existing.get().active()) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS", "User already belongs to this organization");
            }
            requireOwnerForAdminChange(actorId, organization, existing.get().role(), request.role());
            if (organization.ownerUserId().equals(user.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "OWNER_PROTECTED", "Organization owner cannot be reactivated through membership management");
            }
            update(actorId, existing.get(), false, existing.get().role(), request.role(), true);
            return new MemberResponse(user.id(), user.email(), user.displayName(), request.role());
        }
        Instant now = Instant.now();
        Membership member = new Membership(UUID.randomUUID().toString(), user.id(),
                organizationId, request.role(), true, now,
                UUID.randomUUID().toString(), actorId, now, null, false);
        try {
            memberships.save(member);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS", "User already belongs to this organization");
        }
        ensureAudit(member);
        return new MemberResponse(user.id(), user.email(), user.displayName(), request.role());
    }

    public List<MemberResponse> list(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        String ownerUserId = organization(organizationId).ownerUserId();
        List<MemberResponse> result = new ArrayList<>();
        for (Membership membership : memberships.findByOrganizationId(organizationId)) {
            users.findById(membership.userId()).ifPresent(user -> result.add(
                    new MemberResponse(user.id(), user.email(), user.displayName(),
                            ownerUserId.equals(user.id()) ? OrganizationRole.ORG_ADMIN : membership.role(),
                            ownerUserId.equals(user.id()), ownerUserId.equals(user.id()) || membership.active())));
        }
        return result;
    }

    public MemberResponse changeRole(String actorId, String organizationId, String userId,
                                     OrganizationRole role) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        Organization organization = organization(organizationId);
        Membership member = memberships.findByUserIdAndOrganizationId(userId, organizationId)
                .filter(Membership::active)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Active member not found"));
        if (organization.ownerUserId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_PROTECTED", "Organization owner role cannot be changed");
        }
        if (actorId.equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELF_CHANGE", "You cannot change your own role");
        }
        if (!allowedForType(organization.type(), role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role is unavailable for this organization type");
        }
        requireOwnerForAdminChange(actorId, organization, member.role(), role);
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));
        if (member.role() != role) update(actorId, member, true, member.role(), role, true);
        else ensureAudit(member);
        return new MemberResponse(user.id(), user.email(), user.displayName(), role);
    }

    public void deactivate(String actorId, String organizationId, String userId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        Organization organization = organization(organizationId);
        Membership member = memberships.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Member not found"));
        if (organization.ownerUserId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_PROTECTED", "Organization owner cannot be deactivated");
        }
        if (actorId.equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELF_CHANGE", "You cannot deactivate yourself");
        }
        requireOwnerForAdminChange(actorId, organization, member.role(), null);
        if (member.active()) update(actorId, member, true, member.role(), member.role(), false);
        else ensureAudit(member);
    }

    public Page<MemberAuditView> audit(String actorId, String organizationId, String userId, int page) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Membership member = memberships.findByUserIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Member not found"));
        ensureAudit(member);
        return auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                organizationId, "MEMBERSHIP", userId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .map(MemberAuditView::from);
    }

    private Organization organization(String organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
    }

    private void requireOwnerForAdminChange(String actorId, Organization organization,
                                             OrganizationRole current, OrganizationRole next) {
        if ((current == OrganizationRole.ORG_ADMIN || next == OrganizationRole.ORG_ADMIN)
                && !organization.ownerUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the organization owner can manage admins");
        }
    }

    private void update(String actorId, Membership member, boolean active, OrganizationRole currentRole,
                        OrganizationRole nextRole, boolean nextActive) {
        Instant now = Instant.now();
        String changeId = UUID.randomUUID().toString();
        Query query = Query.query(Criteria.where("_id").is(member.id())
                .and("organizationId").is(member.organizationId())
                .and("userId").is(member.userId())
                .and("active").is(active));
        query.addCriteria(Criteria.where("role").is(currentRole));
        if (mongo.updateFirst(query, new Update().set("role", nextRole)
                .set("active", nextActive).set("lastChangeId", changeId)
                .set("lastChangeActorId", actorId).set("lastChangedAt", now)
                .set("previousRole", member.role()).set("previousActive", member.active()),
                Membership.class).getModifiedCount() != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_CHANGED", "Membership changed concurrently; reload it");
        }
        ensureAudit(new Membership(member.id(), member.userId(), member.organizationId(),
                nextRole, nextActive, member.createdAt(), changeId, actorId, now,
                member.role(), member.active()));
    }

    private void ensureAudit(Membership member) {
        if (member.lastChangeId() == null || member.lastChangeActorId() == null
                || member.lastChangedAt() == null || member.previousActive() == null) return;
        AuditAction action = !member.previousActive()
                ? member.previousRole() == null ? AuditAction.MEMBERSHIP_ADDED
                : AuditAction.MEMBERSHIP_REACTIVATED
                : !member.active() ? AuditAction.MEMBERSHIP_DEACTIVATED
                : AuditAction.MEMBERSHIP_ROLE_CHANGED;
        Map<String, String> details = new HashMap<>();
        if (member.previousRole() != null) details.put("previousRole", member.previousRole().name());
        details.put("role", member.role().name());
        try {
            auditEvents.insert(new AuditEvent("membership:" + member.lastChangeId(),
                    member.organizationId(), member.lastChangeActorId(), action,
                    "MEMBERSHIP", member.userId(), member.lastChangedAt(), details));
        } catch (DuplicateKeyException ignored) {
            // The immutable transition was already recorded by a retry.
        }
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

    public MemberResponse findCandidateByEmail(String actorId, String organizationId, String email) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        Organization organization = organization(organizationId);
        if (organization.type() != OrganizationType.TALENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TALENT_ONLY", "Candidates are available in Talent organizations");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        User user = users.findByEmail(normalizedEmail)
                .orElseThrow(this::candidateNotFound);
        Membership member = memberships.findByUserIdAndOrganizationIdAndActiveTrue(user.id(), organizationId)
                .filter(value -> value.role() == OrganizationRole.CANDIDATE)
                .orElseThrow(this::candidateNotFound);
        if (user.disabled() || !user.emailVerified()) throw candidateNotFound();
        return new MemberResponse(user.id(), user.email(), user.displayName(), member.role());
    }

    private ApiException candidateNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND",
                "No active candidate with that email in this organization");
    }

    private boolean allowedForType(OrganizationType type, OrganizationRole role) {
        if (role == OrganizationRole.ORG_ADMIN) return true;
        return switch (type) {
            case ACADEMY -> role == OrganizationRole.INSTRUCTOR || role == OrganizationRole.LEARNER;
            case TALENT -> role == OrganizationRole.RECRUITER || role == OrganizationRole.CANDIDATE;
        };
    }
}
