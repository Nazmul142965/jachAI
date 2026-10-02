package com.jachai.talent.service;

import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.talent.dto.CreateEmailInvitationRequest;
import com.jachai.talent.dto.EmailInvitationView;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import com.jachai.talent.repository.HiringCampaignRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignEmailInvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration MAX_LIFETIME = Duration.ofDays(14);

    private final CampaignEmailInvitationRepository invitations;
    private final HiringCampaignRepository campaigns;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AssignmentRepository assignments;
    private final UserRepository users;
    private final TenantAccessService access;
    private final EmailSender emailSender;
    @Value("${app.public-base-url}") private String publicBaseUrl;

    public EmailInvitationView invite(String actorId, String organizationId, String campaignId,
                                      CreateEmailInvitationRequest request) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.TALENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TALENT_ONLY", "Email invitations require a Talent organization");
        }
        HiringCampaign campaign = campaigns.findByIdAndOrganizationId(campaignId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign not found"));
        if (!campaign.open()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_CLOSED", "Campaign is closed to new invitations");
        }
        Instant now = Instant.now();
        if (!request.availableUntil().isAfter(request.availableFrom())
                || !request.availableUntil().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW", "Availability must end in the future after it starts");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        CampaignEmailInvitation existing = invitations
                .findByOrganizationIdAndCampaignIdAndEmail(organizationId, campaignId, email).orElse(null);
        if (existing != null && existing.acceptedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_INVITED", "This email already accepted this campaign");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(MAX_LIFETIME);
        if (request.availableUntil().isBefore(expiresAt)) expiresAt = request.availableUntil();
        CampaignEmailInvitation invitation = existing == null
                ? new CampaignEmailInvitation(UUID.randomUUID().toString(), organizationId, campaignId, email,
                        campaign.assessmentId(), campaign.assessmentVersionId(), request.attemptsAllowed(),
                        request.availableFrom(), request.availableUntil(), hash(rawToken), now, expiresAt, null, null)
                : existing.reissued(hash(rawToken), now, expiresAt);
        if (existing != null && (existing.attemptsAllowed() != request.attemptsAllowed()
                || !existing.availableFrom().equals(request.availableFrom())
                || !existing.availableUntil().equals(request.availableUntil()))) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_WINDOW_CHANGED",
                    "An invitation already exists with different availability; use its original window");
        }
        try {
            invitation = existing == null ? invitations.insert(invitation) : invitations.save(invitation);
        } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CONFLICT", "Invitation changed concurrently; retry");
        }
        String url = publicBaseUrl + "/?invitation="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        emailSender.sendAssessmentInvitation(email, organization.name(), campaign.title(), url, expiresAt);
        return EmailInvitationView.from(invitation);
    }

    public Assignment accept(String actorId, String rawToken) {
        CampaignEmailInvitation invitation = invitations.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> invalidInvitation());
        User user = users.findById(actorId).orElseThrow(() -> invalidInvitation());
        if (!user.emailVerified() || user.disabled()
                || !user.email().equalsIgnoreCase(invitation.email())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVITATION_ACCOUNT_MISMATCH",
                    "Sign in with the verified email address that received this invitation");
        }
        String assignmentId = assignmentId(invitation.campaignId(), user.id());
        if (invitation.acceptedAt() != null) {
            return ownAssignment(invitation, assignmentId, user.id());
        }
        if (!invitation.expiresAt().isAfter(Instant.now())) throw invalidInvitation();
        ensureMembership(invitation.organizationId(), user.id());
        Assignment assignment = assignments.findByIdAndOrganizationId(assignmentId, invitation.organizationId())
                .orElse(null);
        if (assignment == null) {
            assignment = new Assignment(assignmentId, invitation.organizationId(), invitation.assessmentId(),
                    invitation.assessmentVersionId(), invitation.campaignId(), null, user.id(),
                    invitation.attemptsAllowed(), invitation.availableFrom(), invitation.availableUntil(), Instant.now());
            try {
                assignment = assignments.insert(assignment);
            } catch (DuplicateKeyException exception) {
                assignment = ownAssignment(invitation, assignmentId, user.id());
            }
        } else {
            verifyAssignment(invitation, assignment, user.id());
        }
        try {
            invitations.save(invitation.accepted(Instant.now()));
        } catch (OptimisticLockingFailureException exception) {
            // The deterministic membership and assignment make concurrent acceptance idempotent.
        }
        return assignment;
    }

    private void ensureMembership(String organizationId, String userId) {
        Membership existing = memberships.findByUserIdAndOrganizationId(userId, organizationId).orElse(null);
        if (existing != null) {
            if (!existing.active() || existing.role() != OrganizationRole.CANDIDATE) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT",
                        "Your existing membership cannot accept a candidate invitation");
            }
            return;
        }
        String id = UUID.nameUUIDFromBytes((organizationId + ":" + userId).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            memberships.insert(new Membership(id, userId, organizationId,
                    OrganizationRole.CANDIDATE, true, Instant.now()));
        } catch (DuplicateKeyException exception) {
            Membership raced = memberships.findByUserIdAndOrganizationId(userId, organizationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT",
                            "Membership changed concurrently; retry"));
            if (!raced.active() || raced.role() != OrganizationRole.CANDIDATE) {
                throw new ApiException(HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT",
                        "Your existing membership cannot accept a candidate invitation");
            }
        }
    }

    private Assignment ownAssignment(CampaignEmailInvitation invitation, String assignmentId, String userId) {
        Assignment assignment = assignments.findByIdAndOrganizationId(assignmentId, invitation.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "INVITATION_INCOMPLETE",
                        "Invitation assignment is not ready; retry"));
        verifyAssignment(invitation, assignment, userId);
        return assignment;
    }

    private void verifyAssignment(CampaignEmailInvitation invitation, Assignment assignment, String userId) {
        if (!userId.equals(assignment.assignedUserId())
                || !invitation.campaignId().equals(assignment.campaignId())
                || !invitation.assessmentVersionId().equals(assignment.assessmentVersionId())
                || invitation.attemptsAllowed() != assignment.attemptsAllowed()
                || !invitation.availableFrom().equals(assignment.availableFrom())
                || !invitation.availableUntil().equals(assignment.availableUntil())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CONFLICT",
                    "A different assignment already exists for this campaign");
        }
    }

    private static String assignmentId(String campaignId, String userId) {
        return UUID.nameUUIDFromBytes((campaignId + ":" + userId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException invalidInvitation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVITATION", "Invitation link is invalid or expired");
    }
}
