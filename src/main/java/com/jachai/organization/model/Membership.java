package com.jachai.organization.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("memberships")
@CompoundIndex(name = "member_org_unique", def = "{'userId': 1, 'organizationId': 1}", unique = true)
public record Membership(
        @Id String id,
        String userId,
        String organizationId,
        OrganizationRole role,
        boolean active,
        Instant createdAt) {
}
