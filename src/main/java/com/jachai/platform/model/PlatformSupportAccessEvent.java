package com.jachai.platform.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("platform_support_access_events")
@CompoundIndex(name = "support_access_org_time",
        def = "{'organizationId': 1, 'occurredAt': -1, '_id': -1}")
public record PlatformSupportAccessEvent(
        @Id String id,
        String organizationId,
        String assessmentId,
        String actorUserId,
        String ticketReference,
        String reason,
        Instant occurredAt) { }
