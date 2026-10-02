package com.jachai.talent.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("hiring_campaigns")
@CompoundIndex(name = "campaign_org_created", def = "{'organizationId': 1, 'createdAt': -1}")
public record HiringCampaign(@Id String id, String organizationId, String assessmentId,
                             String assessmentVersionId, String title, String createdBy,
                             boolean open, Instant createdAt, @Version Long revision) {
    public HiringCampaign close() {
        return new HiringCampaign(id, organizationId, assessmentId, assessmentVersionId,
                title, createdBy, false, createdAt, revision);
    }
}
