package com.jachai.platform.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("platform_role_events")
@CompoundIndex(name = "platform_role_target_revision_unique",
        def = "{'targetUserId': 1, 'revision': -1}", unique = true)
public record PlatformRoleEvent(@Id String id, String targetUserId, String actorUserId,
                                PlatformRoleAction action, long revision, Instant occurredAt) { }
