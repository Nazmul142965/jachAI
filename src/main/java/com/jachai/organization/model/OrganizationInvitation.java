package com.jachai.organization.model;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("organization_invitations")
@CompoundIndexes({
        @CompoundIndex(name = "organization_invitation_email_unique",
                def = "{'organizationId': 1, 'email': 1}", unique = true),
        @CompoundIndex(name = "organization_invitation_org_created",
                def = "{'organizationId': 1, 'createdAt': -1, '_id': -1}")
})
public record OrganizationInvitation(
        @Id String id,
        String organizationId,
        String email,
        OrganizationRole role,
        String invitedByUserId,
        @Indexed(unique = true) String tokenHash,
        Instant createdAt,
        @Indexed(expireAfter = "0s") Instant expiresAt,
        Instant acceptedAt,
        Instant revokedAt,
        @Version Long revision) {

    public OrganizationInvitation reissued(OrganizationRole nextRole, String actorId,
                                          String nextTokenHash, Instant nextExpiry) {
        return new OrganizationInvitation(id, organizationId, email, nextRole, actorId,
                nextTokenHash, createdAt, nextExpiry, null, null, revision);
    }

    public OrganizationInvitation accepted(Instant at) {
        return new OrganizationInvitation(id, organizationId, email, role, invitedByUserId,
                tokenHash, createdAt, at.plus(Duration.ofDays(7)), at, revokedAt, revision);
    }

    public OrganizationInvitation revoked(Instant at) {
        return new OrganizationInvitation(id, organizationId, email, role, invitedByUserId,
                tokenHash, createdAt, expiresAt, acceptedAt, at, revision);
    }
}
