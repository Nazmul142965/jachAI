package com.jachai.organization.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.dto.CreateOrganizationInvitationRequest;
import com.jachai.organization.dto.OrganizationInvitationView;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationInvitation;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationInvitationRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.shared.error.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrganizationInvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LIFETIME = Duration.ofDays(7);

    private final OrganizationInvitationRepository invitations;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final MembershipService memberService;
    private final TenantAccessService access;
    private final EmailSender emailSender;
    @Value("${app.public-base-url}") private String publicBaseUrl;

    public OrganizationInvitationView invite(String actorId, String organizationId,
                                              CreateOrganizationInvitationRequest request) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        Organization organization = organization(organizationId);
        validateRole(actorId, organization, request.role());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        users.findByEmail(email).ifPresent(user -> {
            if (organization.ownerUserId().equals(user.id()) || memberships
                    .findByUserIdAndOrganizationId(user.id(), organizationId)
                    .isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS",
                        "This person already has a membership; manage it directly instead");
            }
        });

        OrganizationInvitation existing = invitations.findByOrganizationIdAndEmail(organizationId, email)
                .orElse(null);
        if (existing != null && existing.acceptedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ACCEPTED",
                    "This invitation has already been accepted; manage the membership instead");
        }
        if (existing != null && existing.role() == OrganizationRole.ORG_ADMIN
                && !organization.ownerUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY",
                    "Only the owner can replace an admin invitation");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(LIFETIME);
        OrganizationInvitation next = existing == null
                ? new OrganizationInvitation(UUID.randomUUID().toString(), organizationId, email,
                        request.role(), actorId, hash(token), now, expiresAt, null, null, null)
                : existing.reissued(request.role(), actorId, hash(token), expiresAt);
        try {
            next = existing == null ? invitations.insert(next) : invitations.save(next);
        } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CONFLICT",
                    "Invitation changed concurrently; reload and retry");
        }
        String url = publicBaseUrl.replaceAll("/+$", "") + "/?memberInvitation="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        emailSender.sendOrganizationInvitation(email, organization.name(), request.role().name(), url, expiresAt);
        return OrganizationInvitationView.from(next);
    }

    public Page<OrganizationInvitationView> list(String actorId, String organizationId, Pageable pageable) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        organization(organizationId);
        return invitations.findByOrganizationId(organizationId, pageable)
                .map(OrganizationInvitationView::from);
    }

    public OrganizationInvitationView revoke(String actorId, String organizationId, String invitationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        OrganizationInvitation invitation = invitations.findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Invitation not found"));
        if (invitation.role() == OrganizationRole.ORG_ADMIN
                && !organization(organizationId).ownerUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner can revoke an admin invitation");
        }
        if (invitation.acceptedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ACCEPTED",
                    "Deactivate the member instead of revoking an accepted invitation");
        }
        if (invitation.revokedAt() != null) return OrganizationInvitationView.from(invitation);
        try {
            return OrganizationInvitationView.from(invitations.save(invitation.revoked(Instant.now())));
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CONFLICT",
                    "Invitation changed concurrently; reload and retry");
        }
    }

    public OrganizationInvitationView accept(String userId, String token) {
        OrganizationInvitation invitation = invitations.findByTokenHash(hash(token))
                .orElseThrow(OrganizationInvitationService::invalidInvitation);
        User user = users.findById(userId).orElseThrow(OrganizationInvitationService::invalidInvitation);
        if (user.disabled() || !user.emailVerified() || !user.email().equalsIgnoreCase(invitation.email())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVITATION_ACCOUNT_MISMATCH",
                    "Sign in with the verified email address that received this invitation");
        }
        if (invitation.revokedAt() != null) throw invalidInvitation();
        if (!invitation.expiresAt().isAfter(Instant.now()))
            throw invalidInvitation();
        OrganizationRole invitedRole = invitation.role();
        memberships.findByUserIdAndOrganizationId(user.id(), invitation.organizationId())
                .filter(member -> !member.active() || member.role() != invitedRole)
                .ifPresent(ignored -> { throw membershipConflict(); });
        if (invitation.acceptedAt() == null) {
            Organization organization = organization(invitation.organizationId());
            if (organization.ownerUserId().equals(user.id())) throw membershipConflict();
            access.requireRole(invitation.invitedByUserId(), organization.id(), OrganizationRole.ORG_ADMIN);
            validateRole(invitation.invitedByUserId(), organization, invitation.role());
            try {
                invitation = invitations.save(invitation.accepted(Instant.now()));
            } catch (OptimisticLockingFailureException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CONFLICT",
                        "Invitation changed concurrently; reload and retry");
            }
        }
        memberService.acceptInvitation(invitation, user);
        return OrganizationInvitationView.from(invitation);
    }

    private Organization organization(String organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
    }

    private static void validateRole(String actorId, Organization organization, OrganizationRole role) {
        if (role == OrganizationRole.ORG_ADMIN) {
            if (!organization.ownerUserId().equals(actorId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the owner can invite an admin");
            }
            return;
        }
        boolean allowed = switch (organization.type()) {
            case ACADEMY -> role == OrganizationRole.INSTRUCTOR || role == OrganizationRole.LEARNER;
            case TALENT -> role == OrganizationRole.RECRUITER || role == OrganizationRole.CANDIDATE;
        };
        if (!allowed) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                "Role is unavailable for this organization type");
    }

    private static ApiException membershipConflict() {
        return new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT",
                "An existing membership has a different role");
    }

    private static ApiException invalidInvitation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITATION", "Invitation link is invalid or expired");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
