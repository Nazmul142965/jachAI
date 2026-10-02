package com.jachai.organization.model;

import com.jachai.assessment.model.ResultReleasePolicy;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("organization_settings")
public record OrganizationSettings(@Id String organizationId,
                                   ResultReleasePolicy defaultResultReleasePolicy,
                                   String updatedByUserId, Instant updatedAt,
                                   @Version Long revision) {
    public OrganizationSettings withPolicy(ResultReleasePolicy policy, String actorId, Instant now) {
        return new OrganizationSettings(organizationId, policy, actorId, now, revision);
    }
}
