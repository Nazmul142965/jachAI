package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationInvitation;
import com.jachai.organization.model.OrganizationRole;
import java.time.Instant;

public record OrganizationInvitationView(String id, String organizationId, String email,
                                         OrganizationRole role, Instant createdAt, Instant expiresAt,
                                         boolean accepted, boolean revoked) {
    public static OrganizationInvitationView from(OrganizationInvitation invitation) {
        return new OrganizationInvitationView(invitation.id(), invitation.organizationId(),
                invitation.email(), invitation.role(), invitation.createdAt(), invitation.expiresAt(),
                invitation.acceptedAt() != null, invitation.revokedAt() != null);
    }
}
