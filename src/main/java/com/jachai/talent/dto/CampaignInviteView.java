package com.jachai.talent.dto;

import com.jachai.attempt.model.Assignment;
import java.time.Instant;

public record CampaignInviteView(String assignmentId, String campaignId, String candidateUserId,
                                 String assessmentVersionId, Instant availableFrom, Instant availableUntil) {
    public static CampaignInviteView from(Assignment assignment) {
        return new CampaignInviteView(assignment.id(), assignment.campaignId(), assignment.assignedUserId(),
                assignment.assessmentVersionId(), assignment.availableFrom(), assignment.availableUntil());
    }
}
