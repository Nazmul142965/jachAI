package com.jachai.talent.dto;

import com.jachai.talent.model.CampaignEmailInvitation;
import java.time.Instant;

public record EmailInvitationView(String invitationId, String campaignId, String email,
                                  Instant availableFrom, Instant availableUntil,
                                  Instant expiresAt, boolean accepted) {
    public static EmailInvitationView from(CampaignEmailInvitation invitation) {
        return new EmailInvitationView(invitation.id(), invitation.campaignId(), invitation.email(),
                invitation.availableFrom(), invitation.availableUntil(), invitation.expiresAt(),
                invitation.acceptedAt() != null);
    }
}
