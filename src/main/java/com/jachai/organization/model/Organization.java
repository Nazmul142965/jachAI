package com.jachai.organization.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("organizations")
public record Organization(
        @Id String id,
        String name,
        OrganizationType type,
        String ownerUserId,
        Instant createdAt) {
}
