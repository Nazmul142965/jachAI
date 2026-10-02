package com.jachai.shared.audit;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("audit_events")
@CompoundIndex(name = "audit_tenant_target_time",
        def = "{'organizationId': 1, 'targetType': 1, 'targetId': 1, 'occurredAt': -1}")
public record AuditEvent(@Id String id, String organizationId, String actorUserId,
                         AuditAction action, String targetType, String targetId,
                         Instant occurredAt, Map<String, String> details) {
    public AuditEvent(String id, String organizationId, String actorUserId,
                      AuditAction action, String targetType, String targetId,
                      Instant occurredAt) {
        this(id, organizationId, actorUserId, action, targetType, targetId, occurredAt, Map.of());
    }
}
