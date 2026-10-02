package com.jachai.talent.model;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("talent_review_decisions")
@CompoundIndex(name = "decision_org_campaign", def = "{'organizationId': 1, 'campaignId': 1}")
public record ReviewDecision(@Id String id, String organizationId, String campaignId,
                             String candidateUserId, String attemptId, ReviewDecisionType decision,
                             String note, String reviewerUserId, Instant updatedAt,
                             String auditEventId,
                             @Version Long revision) {
    public ReviewDecision(String id, String organizationId, String campaignId,
                          String candidateUserId, String attemptId, ReviewDecisionType decision,
                          String note, String reviewerUserId, Instant updatedAt, Long revision) {
        this(id, organizationId, campaignId, candidateUserId, attemptId, decision,
                note, reviewerUserId, updatedAt, null, revision);
    }

    public ReviewDecision update(String selectedAttemptId, ReviewDecisionType nextDecision,
                                 String nextNote, String reviewerId, Instant when) {
        return new ReviewDecision(id, organizationId, campaignId, candidateUserId,
                selectedAttemptId, nextDecision, nextNote, reviewerId, when,
                UUID.randomUUID().toString(), revision);
    }
}
