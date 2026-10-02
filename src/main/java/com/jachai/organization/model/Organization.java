package com.jachai.organization.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("organizations")
public record Organization(
        @Id String id,
        String name,
        OrganizationType type,
        @Indexed String ownerUserId,
        Instant createdAt,
        String ownershipTransferId,
        String previousOwnerUserId,
        String ownershipTransferredBy,
        Instant ownershipTransferredAt) {
    public Organization(String id, String name, OrganizationType type,
                        String ownerUserId, Instant createdAt) {
        this(id, name, type, ownerUserId, createdAt, null, null, null, null);
    }
}
