package com.jachai.platform.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("platform_plan_events")
@CompoundIndex(name = "platform_plan_org_revision_unique",
        def = "{'organizationId': 1, 'revision': -1}", unique = true)
public record PlatformPlanEvent(@Id String id, String organizationId, String actorUserId,
                                PlatformPlanAction action, Instant expiresAt, String reason,
                                long revision, Instant occurredAt) { }
