package com.jachai.attempt.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("assignments")
@CompoundIndexes({
        @CompoundIndex(name = "assignment_org_user", def = "{'organizationId': 1, 'assignedUserId': 1}"),
        @CompoundIndex(name = "assignment_org_user_created", def = "{'organizationId': 1, 'assignedUserId': 1, 'createdAt': -1, '_id': -1}"),
        @CompoundIndex(name = "assignment_org_campaign_created", def = "{'organizationId': 1, 'campaignId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "assignment_org_batch_assessment_created", def = "{'organizationId': 1, 'batchAssessmentId': 1, 'createdAt': -1}")
})
public record Assignment(
        @Id String id,
        String organizationId,
        String assessmentId,
        String assessmentVersionId,
        String campaignId,
        String batchAssessmentId,
        String assignedUserId,
        int attemptsAllowed,
        Instant availableFrom,
        Instant availableUntil,
        Instant createdAt) {
}
