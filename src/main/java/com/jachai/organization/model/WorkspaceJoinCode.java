package com.jachai.organization.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("workspace_join_codes")
@CompoundIndex(name = "join_code_org_created", def = "{'organizationId': 1, 'createdAt': -1}")
public record WorkspaceJoinCode(
        @Id String id,
        String organizationId,
        OrganizationRole role,
        @Indexed(unique = true) String codeHash,
        String createdByUserId,
        Instant createdAt,
        @Indexed(expireAfter = "0s") Instant expiresAt,
        Instant revokedAt,
        long redemptions) {
    public WorkspaceJoinCode revoked(Instant at) {
        return new WorkspaceJoinCode(id, organizationId, role, codeHash, createdByUserId,
                createdAt, expiresAt, at, redemptions);
    }
}
