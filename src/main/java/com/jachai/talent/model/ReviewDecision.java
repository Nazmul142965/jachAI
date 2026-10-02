package com.jachai.talent.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("talent_review_decisions")
@CompoundIndex(name = "decision_org_campaign", def = "{'organizationId': 1, 'campaignId': 1}")
public record ReviewDecision(@Id String id, String organizationId, String campaignId,
                             String candidateUserId, String attemptId, ReviewDecisionType decision,
                             String note, String reviewerUserId, Instant updatedAt,
                             @Version Long revision) {
    public ReviewDecision update(String selectedAttemptId, ReviewDecisionType nextDecision,
                                 String nextNote, String reviewerId, Instant when) {
        return new ReviewDecision(id, organizationId, campaignId, candidateUserId,
                selectedAttemptId, nextDecision, nextNote, reviewerId, when, revision);
    }
}
