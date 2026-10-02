package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.model.User;
import com.jachai.academy.repository.CertificateRepository;
import com.jachai.academy.model.Certificate;
import com.jachai.academy.model.CertificateStatus;
import com.jachai.mail.model.EmailJobType;
import com.jachai.mail.model.EmailPayload;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.repository.CampaignEmailInvitationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailLinkGuardTests {
    @Mock EmailVerificationTokenRepository verificationTokens;
    @Mock PasswordResetTokenRepository resetTokens;
    @Mock CampaignEmailInvitationRepository invitations;
    @Mock CertificateRepository certificates;
    @Mock UserRepository users;

    private EmailLinkGuard guard() {
        return new EmailLinkGuard(verificationTokens, resetTokens, invitations, certificates, users);
    }

    @Test
    void currentVerificationTokenMayBeDelivered() {
        when(verificationTokens.findByTokenHash(any())).thenReturn(Optional.of(
                new EmailVerificationToken("id", "hash", "user", Instant.now().plusSeconds(3600))));
        assertTrue(guard().isCurrent(new EmailPayload(EmailJobType.VERIFICATION,
                "person@example.com", "Person", null, "https://example.test/verify?token=secret",
                Instant.now().plusSeconds(3600))));
    }

    @Test
    void rotatedInvitationTokenIsNotDelivered() {
        assertFalse(guard().isCurrent(new EmailPayload(EmailJobType.ASSESSMENT_INVITATION,
                "person@example.com", "Org", "Campaign", "https://example.test/?invitation=old",
                Instant.now().plusSeconds(3600))));
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
    }
}
