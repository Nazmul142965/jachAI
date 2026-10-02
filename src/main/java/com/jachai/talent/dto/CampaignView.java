package com.jachai.talent.dto;

import com.jachai.talent.model.HiringCampaign;
import java.time.Instant;

public record CampaignView(String id, String title, String assessmentId,
                           String assessmentVersionId, boolean open, Instant createdAt) {
    public static CampaignView from(HiringCampaign campaign) {
        return new CampaignView(campaign.id(), campaign.title(), campaign.assessmentId(),
                campaign.assessmentVersionId(), campaign.open(), campaign.createdAt());
    }
}
