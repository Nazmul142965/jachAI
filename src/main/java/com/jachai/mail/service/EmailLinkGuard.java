package com.jachai.mail.service;

import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.academy.model.CertificateStatus;
import com.jachai.academy.repository.CertificateRepository;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.organization.repository.OrganizationInvitationRepository;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class EmailLinkGuard {
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final CampaignEmailInvitationRepository invitations;
    private final OrganizationInvitationRepository organizationInvitations;
    private final CertificateRepository certificates;
    private final AttemptRepository attempts;
    private final AssignmentRepository assignments;
    private final MembershipRepository memberships;
    private final PaymentOrderRepository paymentOrders;
    private final UserRepository users;

    public boolean isCurrent(EmailPayload payload) {
        if (payload.type() == EmailJobType.CERTIFICATE_ISSUED) {
            return currentCertificate(payload);
        }
        if (payload.type() == EmailJobType.RESULT_RELEASED) {
            return currentResult(payload);
        }
        if (payload.type() == EmailJobType.ASSIGNMENT_REMINDER) {
            return currentAssignmentReminder(payload);
        }
        if (payload.type() == EmailJobType.BILLING_NOTICE) {
            return currentBillingNotice(payload);
        }
        String token = tokenFrom(payload.url(),
                payload.type() == EmailJobType.ASSESSMENT_INVITATION ? "invitation"
                        : payload.type() == EmailJobType.ORGANIZATION_INVITATION ? "memberInvitation" : "token");
        if (token == null) return false;
        String hash = hash(token);
        Instant now = Instant.now();
        return switch (payload.type()) {
            case VERIFICATION -> verificationTokens.findByTokenHash(hash)
                    .filter(value -> value.expiresAt().isAfter(now)).isPresent();
            case PASSWORD_RESET -> resetTokens.findByTokenHash(hash)
                    .filter(value -> value.expiresAt().isAfter(now)).isPresent();
            case ASSESSMENT_INVITATION -> invitations.findByTokenHash(hash)
                    .filter(value -> value.acceptedAt() == null && value.expiresAt().isAfter(now)).isPresent();
            case ORGANIZATION_INVITATION -> organizationInvitations.findByTokenHash(hash)
                    .filter(value -> value.acceptedAt() == null && value.revokedAt() == null
                            && value.expiresAt().isAfter(now)
                            && value.email().equalsIgnoreCase(payload.recipient())).isPresent();
            case CERTIFICATE_ISSUED -> false;
            case RESULT_RELEASED -> false;
            case ASSIGNMENT_REMINDER -> false;
            case BILLING_NOTICE -> false;
        };
    }

    private boolean currentBillingNotice(EmailPayload payload) {
        if (payload.resourceId() == null) return false;
        try {
            URI url = URI.create(payload.url());
            if (!"/".equals(url.getPath()) || url.getQuery() != null) return false;
            return paymentOrders.findById(payload.resourceId())
                    .filter(order -> order.status() == PaymentOrderStatus.APPLIED)
                    .flatMap(order -> users.findById(order.actorUserId()))
                    .filter(user -> user.emailVerified() && !user.disabled()
                            && user.email().equalsIgnoreCase(payload.recipient()))
                    .isPresent();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean currentAssignmentReminder(EmailPayload payload) {
        if (payload.resourceId() == null) return false;
        try {
            URI url = URI.create(payload.url());
            if (!"/".equals(url.getPath()) || url.getQuery() != null) return false;
            Instant now = Instant.now();
            return assignments.findById(payload.resourceId())
                    .filter(assignment -> assignment.availableUntil().isAfter(now))
                    .filter(assignment -> !attempts.existsByOrganizationIdAndAssignmentIdAndStatus(
                            assignment.organizationId(), assignment.id(), AttemptStatus.SUBMITTED))
                    .filter(assignment -> memberships.findByUserIdAndOrganizationIdAndActiveTrue(
                            assignment.assignedUserId(), assignment.organizationId())
                            .filter(member -> member.role() == OrganizationRole.LEARNER
                                    || member.role() == OrganizationRole.CANDIDATE).isPresent())
                    .flatMap(assignment -> users.findById(assignment.assignedUserId()))
                    .filter(user -> user.emailVerified() && !user.disabled()
                            && user.email().equalsIgnoreCase(payload.recipient()))
                    .isPresent();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean currentResult(EmailPayload payload) {
        if (payload.resourceId() == null) return false;
        try {
            URI url = URI.create(payload.url());
            if (!"/".equals(url.getPath()) || url.getQuery() != null) return false;
            return attempts.findById(payload.resourceId())
                    .filter(attempt -> attempt.status() == AttemptStatus.SUBMITTED
                            && attempt.resultReleasedAt() != null)
                    .flatMap(attempt -> users.findById(attempt.userId()))
                    .filter(user -> user.email().equalsIgnoreCase(payload.recipient()))
                    .isPresent();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean currentCertificate(EmailPayload payload) {
        if (payload.resourceId() == null) return false;
        try {
            String path = URI.create(payload.url()).getPath();
            return certificates.findById(payload.resourceId())
                    .filter(certificate -> certificate.status() == CertificateStatus.ISSUED
                            && path.equals("/certificates/" + certificate.verificationCode()))
                    .flatMap(certificate -> users.findById(certificate.learnerUserId()))
                    .filter(user -> user.email().equalsIgnoreCase(payload.recipient()))
                    .isPresent();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String tokenFrom(String url, String parameter) {
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return null;
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && parameter.equals(pair[0])) {
                    return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return null;
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
