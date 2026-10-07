package com.jachai.organization.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.dto.CreatedWorkspaceJoinCode;
import com.jachai.organization.dto.OrganizationResponse;
import com.jachai.organization.dto.WorkspaceJoinCodeView;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.model.WorkspaceJoinCode;
import com.jachai.organization.model.WorkspaceJoinRedemption;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.repository.WorkspaceJoinCodeRepository;
import com.jachai.organization.repository.WorkspaceJoinRedemptionRepository;
import com.jachai.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkspaceJoinCodeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration LIFETIME = Duration.ofDays(7);
    private static final long MAX_ACTIVE_CODES = 5;

    private final WorkspaceJoinCodeRepository codes;
    private final WorkspaceJoinRedemptionRepository redemptions;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final MembershipService memberService;
    private final TenantAccessService access;
    private final MongoTemplate mongo;

    public CreatedWorkspaceJoinCode create(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found"));
        Instant now = Instant.now();
        if (codes.countByOrganizationIdAndRevokedAtIsNullAndExpiresAtAfter(organizationId, now)
                >= MAX_ACTIVE_CODES) {
            throw new ApiException(HttpStatus.CONFLICT, "JOIN_CODE_LIMIT",
                    "Revoke an active join code before creating another");
        }
        OrganizationRole role = organization.type() == OrganizationType.ACADEMY
                ? OrganizationRole.LEARNER : OrganizationRole.CANDIDATE;
        String raw = newCode();
        WorkspaceJoinCode saved = codes.insert(new WorkspaceJoinCode(UUID.randomUUID().toString(),
                organizationId, role, hash(raw), actorId, now, now.plus(LIFETIME), null, 0, 0));
        return new CreatedWorkspaceJoinCode(saved.id(), raw, role, saved.expiresAt());
    }

    public Page<WorkspaceJoinCodeView> list(String actorId, String organizationId, Pageable pageable) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        return codes.findByOrganizationId(organizationId, pageable).map(this::view);
    }

    public WorkspaceJoinCodeView revoke(String actorId, String organizationId, String codeId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        WorkspaceJoinCode current = codes.findByIdAndOrganizationId(codeId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Join code not found"));
        if (current.revokedAt() == null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(codeId)
                    .and("organizationId").is(organizationId).and("revokedAt").is(null)),
                    new Update().set("revokedAt", Instant.now()), WorkspaceJoinCode.class);
        }
        return view(codes.findByIdAndOrganizationId(codeId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Join code not found")));
    }

    public OrganizationResponse join(String userId, String enteredCode) {
        String normalized = normalize(enteredCode);
        WorkspaceJoinCode code = codes.findByCodeHash(hash(normalized))
                .orElseThrow(WorkspaceJoinCodeService::invalidCode);
        User user = users.findById(userId)
                .filter(account -> account.emailVerified() && !account.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE",
                        "Verify your email before joining a workspace"));
        Organization organization = organizations.findById(code.organizationId())
                .orElseThrow(WorkspaceJoinCodeService::invalidCode);
        String redemptionId = code.id() + ":" + user.id();
        WorkspaceJoinRedemption prior = redemptions.findById(redemptionId).orElse(null);
        if (memberships.findByUserIdAndOrganizationIdAndActiveTrue(userId, organization.id()).isPresent()) {
            // A prior join may have created the membership and then failed before marking its
            // redemption complete. Repair that outcome even if the code expired meanwhile.
            if (prior == null && !isActive(code)) throw invalidCode();
            memberService.joinWithParticipantCode(user, organization, code.role());
            if (prior != null) complete(prior);
            return new OrganizationResponse(organization.id(), organization.name(), organization.type(),
                    code.role(), false);
        }
        if (!isActive(code)) throw invalidCode();
        WorkspaceJoinCode claimed = mongo.findAndModify(Query.query(Criteria.where("_id").is(code.id())
                        .and("revokedAt").is(null).and("expiresAt").gt(Instant.now())),
                // This is an atomic validity fence against revoke/expiry, not the displayed
                // count. Counting claims here overstates successful joins after failures/retries.
                new Update().inc("claimRevision", 1L), FindAndModifyOptions.options().returnNew(true),
                WorkspaceJoinCode.class);
        if (claimed == null) throw invalidCode();
        WorkspaceJoinRedemption redemption = prior == null ? reserve(redemptionId, code, user) : prior;
        memberService.joinWithParticipantCode(user, organization, code.role());
        complete(redemption);
        return new OrganizationResponse(organization.id(), organization.name(), organization.type(),
                code.role(), false);
    }

    private WorkspaceJoinCodeView view(WorkspaceJoinCode code) {
        // Existing pre-ledger counts remain visible; new joins are counted once per code-user pair.
        return WorkspaceJoinCodeView.from(code,
                redemptions.countByCodeIdAndCompletedAtIsNotNull(code.id()));
    }

    private WorkspaceJoinRedemption reserve(String id, WorkspaceJoinCode code, User user) {
        try {
            return redemptions.insert(new WorkspaceJoinRedemption(id, code.id(), user.id(),
                    Instant.now(), null, code.expiresAt().plus(Duration.ofDays(7))));
        } catch (DuplicateKeyException raced) {
            return redemptions.findById(id).orElseThrow(() -> raced);
        }
    }

    private void complete(WorkspaceJoinRedemption redemption) {
        var changed = mongo.updateFirst(Query.query(Criteria.where("_id").is(redemption.id())
                        .and("completedAt").is(null)),
                new Update().set("completedAt", Instant.now()), WorkspaceJoinRedemption.class);
        if (changed.getMatchedCount() == 0 && redemptions.findById(redemption.id())
                .filter(value -> value.completedAt() != null).isEmpty()) {
            throw new IllegalStateException("Join redemption could not be completed; retry the join");
        }
    }

    private static boolean isActive(WorkspaceJoinCode code) {
        return code.revokedAt() == null && code.expiresAt().isAfter(Instant.now());
    }

    private static String newCode() {
        StringBuilder value = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) value.append('-');
            value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    private static String normalize(String input) {
        String normalized = input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (normalized.length() != 16 || normalized.chars().anyMatch(c -> ALPHABET.indexOf(c) < 0))
            throw invalidCode();
        return normalized;
    }

    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(code.replace("-", "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOIN_CODE",
                "Join code is invalid, expired, or revoked");
    }
}
