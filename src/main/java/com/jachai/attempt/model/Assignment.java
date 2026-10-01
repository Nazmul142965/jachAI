package com.jachai.attempt.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("assignments")
@CompoundIndex(name = "assignment_org_user", def = "{'organizationId': 1, 'assignedUserId': 1}")
public record Assignment(
        @Id String id,
        String organizationId,
        String assessmentId,
        String assessmentVersionId,
        String assignedUserId,
        int attemptsAllowed,
        Instant availableFrom,
        Instant availableUntil,
        Instant createdAt) {
}
