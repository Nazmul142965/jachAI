package com.jachai.talent.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("campaign_email_invitations")
@CompoundIndex(name = "email_invitation_campaign_email",
        def = "{'organizationId': 1, 'campaignId': 1, 'email': 1}", unique = true)
public record CampaignEmailInvitation(
        @Id String id, String organizationId, String campaignId, String email,
        String assessmentId, String assessmentVersionId, int attemptsAllowed,
        Instant availableFrom, Instant availableUntil,
        @Indexed(unique = true) String tokenHash, Instant createdAt,
        @Indexed(expireAfter = "0s") Instant expiresAt,
        Instant acceptedAt, @Version Long revision) {

    public CampaignEmailInvitation accepted(Instant when) {
        return new CampaignEmailInvitation(id, organizationId, campaignId, email,
                assessmentId, assessmentVersionId, attemptsAllowed, availableFrom,
                availableUntil, tokenHash, createdAt, expiresAt, when, revision);
    }

    public CampaignEmailInvitation reissued(String nextTokenHash, Instant nextExpiry) {
        return new CampaignEmailInvitation(id, organizationId, campaignId, email,
                assessmentId, assessmentVersionId, attemptsAllowed, availableFrom,
                availableUntil, nextTokenHash, createdAt, nextExpiry, null, revision);
    }
}
