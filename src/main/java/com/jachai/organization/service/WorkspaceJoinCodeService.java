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
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.repository.WorkspaceJoinCodeRepository;
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
                organizationId, role, hash(raw), actorId, now, now.plus(LIFETIME), null, 0));
        return new CreatedWorkspaceJoinCode(saved.id(), raw, role, saved.expiresAt());
    }

    public Page<WorkspaceJoinCodeView> list(String actorId, String organizationId, Pageable pageable) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        return codes.findByOrganizationId(organizationId, pageable).map(WorkspaceJoinCodeView::from);
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
        return WorkspaceJoinCodeView.from(codes.findByIdAndOrganizationId(codeId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Join code not found")));
    }

    public OrganizationResponse join(String userId, String enteredCode) {
        String normalized = normalize(enteredCode);
        WorkspaceJoinCode code = codes.findByCodeHash(hash(normalized))
                .orElseThrow(WorkspaceJoinCodeService::invalidCode);
        Instant now = Instant.now();
        if (code.revokedAt() != null || !code.expiresAt().isAfter(now)) throw invalidCode();
        User user = users.findById(userId)
                .filter(account -> account.emailVerified() && !account.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE",
                        "Verify your email before joining a workspace"));
        Organization organization = organizations.findById(code.organizationId())
                .orElseThrow(WorkspaceJoinCodeService::invalidCode);
        if (memberships.findByUserIdAndOrganizationIdAndActiveTrue(userId, organization.id()).isPresent()) {
            memberService.joinWithParticipantCode(user, organization, code.role());
            return new OrganizationResponse(organization.id(), organization.name(), organization.type(),
                    code.role(), false);
        }
        WorkspaceJoinCode claimed = mongo.findAndModify(Query.query(Criteria.where("_id").is(code.id())
                        .and("revokedAt").is(null).and("expiresAt").gt(Instant.now())),
                new Update().inc("redemptions", 1L), FindAndModifyOptions.options().returnNew(true),
                WorkspaceJoinCode.class);
        if (claimed == null) throw invalidCode();
        memberService.joinWithParticipantCode(user, organization, code.role());
        return new OrganizationResponse(organization.id(), organization.name(), organization.type(),
                code.role(), false);
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
