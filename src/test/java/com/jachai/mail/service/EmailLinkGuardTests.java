package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.model.PasswordResetToken;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.model.User;
import com.jachai.academy.repository.CertificateRepository;
import com.jachai.academy.repository.InstructorFeedbackRepository;
import com.jachai.academy.model.InstructorFeedback;
import com.jachai.academy.model.FeedbackStatus;
import com.jachai.academy.model.Certificate;
import com.jachai.academy.model.CertificateStatus;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.model.Assignment;
import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.organization.model.OrganizationInvitation;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.OrganizationInvitationRepository;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.model.Membership;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailLinkGuardTests {
    @Mock EmailVerificationTokenRepository verificationTokens;
    @Mock PasswordResetTokenRepository resetTokens;
    @Mock CampaignEmailInvitationRepository invitations;
    @Mock OrganizationInvitationRepository organizationInvitations;
    @Mock CertificateRepository certificates;
    @Mock InstructorFeedbackRepository feedback;
    @Mock AttemptRepository attempts;
    @Mock AssignmentRepository assignments;
    @Mock MembershipRepository memberships;
    @Mock PaymentOrderRepository paymentOrders;
    @Mock UserRepository users;

    private EmailLinkGuard guard() {
        return new EmailLinkGuard(verificationTokens, resetTokens, invitations,
                organizationInvitations, certificates, feedback, attempts, assignments, memberships,
                paymentOrders, users);
    }

    @Test
    void currentVerificationTokenMayBeDelivered() {
        when(verificationTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new EmailVerificationToken("id", "hash", "user", Instant.now().plusSeconds(3600))));
        when(users.findById("user")).thenReturn(Optional.of(new User("user",
                "person@example.com", "Person", null, false, false, 0, Instant.now())));
        assertTrue(guard().isCurrent(new EmailPayload(EmailJobType.VERIFICATION,
                "person@example.com", "Person", null, "https://example.test/verify?token=secret",
                Instant.now().plusSeconds(3600))));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.VERIFICATION,
                "wrong@example.com", "Person", null, "https://example.test/verify?token=secret",
                Instant.now().plusSeconds(3600))));
    }

    @Test
    void verificationIsNotEmailedAfterTheAccountRetentionDeadline() {
        Instant now = Instant.now();
        when(verificationTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new EmailVerificationToken("id", "hash", "user", now.plusSeconds(3600))));
        when(users.findById("user")).thenReturn(Optional.of(new User("user",
                "person@example.com", "Person", null, false, false, 0,
                now.minusSeconds(8 * 24 * 3600L))));

        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.VERIFICATION,
                "person@example.com", "Person", null,
                "https://example.test/verify?token=secret", now.plusSeconds(3600))));
    }

    @Test
    void replacedLegacyVerificationLinkIsNotDelivered() {
        when(verificationTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new EmailVerificationToken("old-random-id", "old-hash", "user",
                        Instant.now().plusSeconds(3600))));
        when(verificationTokens.findById("user")).thenReturn(Optional.of(
                new EmailVerificationToken("user", "new-hash", "user",
                        Instant.now().plusSeconds(3600))));

        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.VERIFICATION,
                "person@example.com", "Person", null,
                "https://example.test/verify?token=old-link", Instant.now().plusSeconds(3600))));
    }

    @Test
    void resetTokenMustBelongToCurrentVerifiedRecipient() {
        when(resetTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new PasswordResetToken("id", "hash", "user", Instant.now().plusSeconds(3600))));
        when(users.findById("user")).thenReturn(Optional.of(new User("user",
                "person@example.com", "Person", null, true, false, 0, Instant.now())));
        EmailPayload valid = new EmailPayload(EmailJobType.PASSWORD_RESET,
                "person@example.com", "Person", null,
                "https://example.test/reset-password.html?token=secret", Instant.now().plusSeconds(3600));
        assertTrue(guard().isCurrent(valid));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.PASSWORD_RESET,
                "wrong@example.com", "Person", null, valid.url(), valid.validUntil())));
    }

    @Test
    void replacedLegacyResetLinkIsNotDelivered() {
        when(resetTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new PasswordResetToken("old-random-id", "old-hash", "user",
                        Instant.now().plusSeconds(3600))));
        when(resetTokens.findById("user")).thenReturn(Optional.of(
                new PasswordResetToken("user", "new-hash", "user",
                        Instant.now().plusSeconds(3600))));

        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.PASSWORD_RESET,
                "person@example.com", "Person", null,
                "https://example.test/reset-password.html?token=old-link",
                Instant.now().plusSeconds(3600))));
    }

    @Test
    void resetMailFromBeforePasswordChangeIsNotDelivered() {
        when(resetTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new PasswordResetToken("user", "hash", "user",
                        Instant.now().plusSeconds(3600), 0)));
        when(users.findById("user")).thenReturn(Optional.of(new User("user",
                "person@example.com", "Person", "changed-hash", true, false, 1, Instant.now())));

        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.PASSWORD_RESET,
                "person@example.com", "Person", null,
                "https://example.test/reset-password.html?token=old-link",
                Instant.now().plusSeconds(3600))));
    }

    @Test
    void rotatedInvitationTokenIsNotDelivered() {
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                "person@example.com", "Org", "Campaign", "https://example.test/?invitation=old",
                Instant.now().plusSeconds(3600))));
    }

    @Test
    void assessmentInvitationMustMatchRecipientAndOpenWindow() {
        Instant now = Instant.now();
        CampaignEmailInvitation pending = new CampaignEmailInvitation("id", "org", "campaign",
                "person@example.com", "assessment", "version", 1, now.minusSeconds(60),
                now.plusSeconds(3600), "hash", now, now.plusSeconds(3600), null, 1L);
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(pending));
        EmailPayload valid = new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                "person@example.com", "Org", "Campaign",
                "https://example.test/?invitation=secret", now.plusSeconds(3600));
        assertTrue(guard().isCurrent(valid));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                "wrong@example.com", "Org", "Campaign", valid.url(), valid.validUntil())));
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(new CampaignEmailInvitation(
                "id", "org", "campaign", "person@example.com", "assessment", "version", 1,
                now.minusSeconds(3600), now.minusSeconds(1), "hash", now.minusSeconds(3600),
                now.plusSeconds(3600), null, 1L)));
        assertFalse(guard().isCurrent(valid));
    }

    @Test
    void acceptedInvitationIsNotDeliveredAgain() {
        when(invitations.findByTokenHash(any())).thenReturn(Optional.of(new CampaignEmailInvitation(
                "id", "org", "campaign", "person@example.com", "assessment", "version", 1,
                Instant.now(), Instant.now().plusSeconds(3600), "hash", Instant.now(),
                Instant.now().plusSeconds(3600), Instant.now(), 1L)));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                "person@example.com", "Org", "Campaign", "https://example.test/?invitation=secret",
                Instant.now().plusSeconds(3600))));
    }

    @Test
    void currentOrganizationInvitationCanBeDeliveredButAcceptedOrWrongRecipientCannot() {
        OrganizationInvitation pending = new OrganizationInvitation("id", "org", "person@example.com",
                OrganizationRole.LEARNER, "owner", "hash", Instant.now(),
                Instant.now().plusSeconds(3600), null, null, 1L);
        when(organizationInvitations.findByTokenHash(any())).thenReturn(Optional.of(pending));
        EmailPayload valid = new EmailPayload(EmailJobType.ORGANIZATION_INVITATION,
                "person@example.com", "Org", "LEARNER",
                "https://example.test/?memberInvitation=secret", Instant.now().plusSeconds(3600));

        assertTrue(guard().isCurrent(valid));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.ORGANIZATION_INVITATION,
                "wrong@example.com", "Org", "LEARNER", valid.url(), valid.validUntil())));

        when(organizationInvitations.findByTokenHash(any())).thenReturn(Optional.of(pending.accepted(Instant.now())));
        assertFalse(guard().isCurrent(valid));
    }

    @Test
    void revokedCertificateEmailIsNotDelivered() {
        Certificate revoked = new Certificate("cert", "org", "learner", "attempt", "version",
                "code", CertificateStatus.REVOKED, Instant.now(), Instant.now(), 1L);
        when(certificates.findById("cert")).thenReturn(Optional.of(revoked));

        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.CERTIFICATE_ISSUED,
                "learner@example.com", "Learner", null, "https://example.test/certificates/code",
                Instant.now().plusSeconds(3600), "cert")));
    }

    @Test
    void certificateEmailMustMatchCurrentRecipientAndCode() {
        Certificate issued = new Certificate("cert", "org", "learner", "attempt", "version",
                "code", CertificateStatus.ISSUED, Instant.now(), null, 1L);
        when(certificates.findById("cert")).thenReturn(Optional.of(issued));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, false, 0, Instant.now())));

        assertTrue(guard().isCurrent(new EmailPayload(EmailJobType.CERTIFICATE_ISSUED,
                "learner@example.com", "Learner", null, "https://example.test/certificates/code",
                Instant.now().plusSeconds(3600), "cert")));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.CERTIFICATE_ISSUED,
                "other@example.com", "Learner", null, "https://example.test/certificates/code",
                Instant.now().plusSeconds(3600), "cert")));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, true, 0, Instant.now())));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.CERTIFICATE_ISSUED,
                "learner@example.com", "Learner", null, "https://example.test/certificates/code",
                Instant.now().plusSeconds(3600), "cert")));
    }

    @Test
    void resultEmailRequiresReleasedAttemptAndCurrentRecipient() {
        Instant now = Instant.now();
        Attempt released = new Attempt("attempt", "org", "assignment", "version", "learner", 1,
                now.minusSeconds(60), now, AttemptStatus.SUBMITTED, Map.of(), now, 80, true, now, 1L);
        when(attempts.findById("attempt")).thenReturn(Optional.of(released));
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.LEARNER, true, now)));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, false, 0, now)));

        assertTrue(guard().isCurrent(new EmailPayload(EmailJobType.RESULT_RELEASED,
                "learner@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "attempt")));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.RESULT_RELEASED,
                "other@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "attempt")));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.RESULT_RELEASED,
                "learner@example.com", "Learner", null, "https://example.test/other",
                now.plusSeconds(3600), "attempt")));
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("learner", "org"))
                .thenReturn(Optional.empty());
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.RESULT_RELEASED,
                "learner@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "attempt")));
    }

    @Test
    void pendingResultEmailIsNotDelivered() {
        Instant now = Instant.now();
        when(attempts.findById("attempt")).thenReturn(Optional.of(new Attempt("attempt", "org",
                "assignment", "version", "learner", 1, now.minusSeconds(60), now,
                AttemptStatus.SUBMITTED, Map.of(), now, 80, true, null, 1L)));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.RESULT_RELEASED,
                "learner@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "attempt")));
    }

    @Test
    void feedbackNoticeRequiresPublishedFeedbackActiveLearnerAndMatchingEmail() {
        Instant now = Instant.now();
        when(feedback.findById("feedback-1")).thenReturn(Optional.of(new InstructorFeedback(
                "feedback-1", "org", "attempt", "learner", "teacher", "Good", "Practice",
                FeedbackStatus.PUBLISHED, now, now, now, 1L)));
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.LEARNER, true, now)));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, false, 0, now)));

        assertTrue(guard().isCurrent(new EmailPayload(EmailJobType.FEEDBACK_PUBLISHED,
                "learner@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "feedback-1")));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.FEEDBACK_PUBLISHED,
                "other@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "feedback-1")));
    }

    @Test
    void draftFeedbackNoticeIsNotDelivered() {
        Instant now = Instant.now();
        when(feedback.findById("feedback-1")).thenReturn(Optional.of(new InstructorFeedback(
                "feedback-1", "org", "attempt", "learner", "teacher", "Good", "Practice",
                FeedbackStatus.DRAFT, now, now, null, 0L)));

        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.FEEDBACK_PUBLISHED,
                "learner@example.com", "Learner", null, "https://example.test/",
                now.plusSeconds(3600), "feedback-1")));
    }

    @Test
    void assignmentReminderRequiresCurrentMemberEmailAndUnfinishedWindow() {
        Instant now = Instant.now();
        when(assignments.findById("assignment")).thenReturn(Optional.of(new Assignment(
                "assignment", "org", "assessment", "assessment:1", null, null,
                "learner", 1, now.minusSeconds(60), now.plusSeconds(3600), now)));
        when(memberships.findByUserIdAndOrganizationIdAndActiveTrue("learner", "org"))
                .thenReturn(Optional.of(new Membership("member", "learner", "org",
                        OrganizationRole.LEARNER, true, now)));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, false, 0, now)));
        EmailPayload payload = new EmailPayload(EmailJobType.ASSIGNMENT_REMINDER,
                "learner@example.com", "Learner", "Assessment", "https://example.test/",
                now.plusSeconds(3600), "assignment");

        assertTrue(guard().isCurrent(payload));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.ASSIGNMENT_REMINDER,
                "other@example.com", "Learner", "Assessment", "https://example.test/",
                now.plusSeconds(3600), "assignment")));
        when(attempts.existsByOrganizationIdAndAssignmentIdAndStatus(
                "org", "assignment", AttemptStatus.SUBMITTED)).thenReturn(true);
        assertFalse(guard().isCurrent(payload));
    }

    @Test
    void billingNoticeRequiresAppliedOrderAndCurrentBuyerEmail() {
        Instant now = Instant.now();
        PaymentOrder applied = new PaymentOrder("order", "org", "buyer", PaymentProvider.BKASH,
                PaymentOrderStatus.APPLIED, new java.math.BigDecimal("100.00"), null, null,
                null, now, now, now, 2L);
        when(paymentOrders.findById("order")).thenReturn(Optional.of(applied));
        when(users.findById("buyer")).thenReturn(Optional.of(new User("buyer",
                "buyer@example.com", "Buyer", null, true, false, 0, now)));

        assertTrue(guard().isCurrent(new EmailPayload(EmailJobType.BILLING_NOTICE,
                "buyer@example.com", "Buyer", "Sandbox order", "https://example.test/",
                now.plusSeconds(3600), "order")));
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.BILLING_NOTICE,
                "other@example.com", "Buyer", "Sandbox order", "https://example.test/",
                now.plusSeconds(3600), "order")));
    }
}
