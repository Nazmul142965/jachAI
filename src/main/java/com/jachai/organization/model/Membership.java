package com.jachai.organization.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("memberships")
@CompoundIndexes({
        @CompoundIndex(name = "member_org_unique", def = "{'userId': 1, 'organizationId': 1}", unique = true),
        @CompoundIndex(name = "member_org_role_active", def = "{'organizationId': 1, 'role': 1, 'active': 1}")
})
public record Membership(
        @Id String id,
        String userId,
        String organizationId,
        OrganizationRole role,
        boolean active,
        Instant createdAt) {
}
