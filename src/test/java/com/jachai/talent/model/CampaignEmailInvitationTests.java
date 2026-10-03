package com.jachai.talent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CampaignEmailInvitationTests {
    @Test
    void acceptedRecordSurvivesOriginalLinkExpiryForRepair() {
        Instant acceptedAt = Instant.parse("2026-10-04T12:00:00Z");
        CampaignEmailInvitation pending = new CampaignEmailInvitation("invite", "org", "campaign",
                "candidate@example.com", "assessment", "assessment:1", 1,
                acceptedAt.minusSeconds(60), acceptedAt.plusSeconds(1), "token-hash",
                acceptedAt.minusSeconds(60), acceptedAt.plusSeconds(1), null, 1L);

        CampaignEmailInvitation accepted = pending.accepted(acceptedAt);

        assertEquals(acceptedAt, accepted.acceptedAt());
        assertEquals(acceptedAt.plus(Duration.ofDays(7)), accepted.expiresAt());
        assertEquals(pending.tokenHash(), accepted.tokenHash());
    }

    @Test
    void acceptanceDoesNotShortenLongerExistingRetention() {
        Instant acceptedAt = Instant.parse("2026-10-04T12:00:00Z");
        Instant originalExpiry = acceptedAt.plus(Duration.ofDays(10));
        CampaignEmailInvitation pending = new CampaignEmailInvitation("invite", "org", "campaign",
                "candidate@example.com", "assessment", "assessment:1", 1,
                acceptedAt.minusSeconds(60), originalExpiry, "token-hash",
                acceptedAt.minusSeconds(60), originalExpiry, null, 1L);

        assertEquals(originalExpiry, pending.accepted(acceptedAt).expiresAt());
    }

    @Test
    void reissuePreservesInitialQuotaMonthWhileRotatingLink() {
        Instant firstIssued = Instant.parse("2026-09-30T23:00:00Z");
        Instant reissuedAt = Instant.parse("2026-10-02T10:00:00Z");
        CampaignEmailInvitation first = new CampaignEmailInvitation("invite", "org", "campaign",
                "candidate@example.com", "assessment", "assessment:1", 1,
                firstIssued, reissuedAt.plusSeconds(3600), "old-hash", firstIssued,
                firstIssued.plusSeconds(3600), null, 1L);

        CampaignEmailInvitation rotated = first.reissued("new-hash", reissuedAt.plusSeconds(3600));

        assertEquals(firstIssued, rotated.createdAt());
        assertEquals("new-hash", rotated.tokenHash());
        assertEquals(reissuedAt.plusSeconds(3600), rotated.expiresAt());
        assertNull(rotated.acceptedAt());
    }
}
