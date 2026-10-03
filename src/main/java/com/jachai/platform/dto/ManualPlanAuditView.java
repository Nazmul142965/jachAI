package com.jachai.platform.dto;

import com.jachai.platform.model.PlatformPlanAction;
import com.jachai.platform.model.PlatformPlanEvent;
import java.time.Instant;

public record ManualPlanAuditView(String id, String organizationId, String actorUserId,
                                  PlatformPlanAction action, Instant expiresAt, String reason,
                                  long revision, Instant occurredAt) {
    public static ManualPlanAuditView from(PlatformPlanEvent event) {
        return new ManualPlanAuditView(event.id(), event.organizationId(), event.actorUserId(),
                event.action(), event.expiresAt(), event.reason(), event.revision(), event.occurredAt());
    }
}
