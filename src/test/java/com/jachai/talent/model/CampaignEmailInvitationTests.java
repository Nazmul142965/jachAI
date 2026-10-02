package com.jachai.talent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CampaignEmailInvitationTests {
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
