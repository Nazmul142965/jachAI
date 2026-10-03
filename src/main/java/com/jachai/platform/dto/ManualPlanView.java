package com.jachai.platform.dto;

import com.jachai.platform.model.PlatformPlanAction;
import com.jachai.platform.model.PlatformPlanEvent;
import java.time.Instant;

public record ManualPlanView(String organizationId, boolean active, PlatformPlanAction latestAction,
                             Instant expiresAt, long revision, Instant changedAt) {
    public static ManualPlanView from(String organizationId, PlatformPlanEvent event, Instant now) {
        if (event == null) return new ManualPlanView(organizationId, false, null, null, 0, null);
        return new ManualPlanView(organizationId,
                event.action() == PlatformPlanAction.GRANTED && now.isBefore(event.expiresAt()),
                event.action(), event.expiresAt(), event.revision(), event.occurredAt());
    }
}
