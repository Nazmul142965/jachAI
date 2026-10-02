package com.jachai.mail.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jachai.auth.model.EmailVerificationToken;
import com.jachai.auth.repository.EmailVerificationTokenRepository;
import com.jachai.auth.repository.PasswordResetTokenRepository;
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

    private EmailLinkGuard guard() {
        return new EmailLinkGuard(verificationTokens, resetTokens, invitations);
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
}
