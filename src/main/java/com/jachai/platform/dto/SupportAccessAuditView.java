package com.jachai.platform.dto;

import com.jachai.platform.model.PlatformSupportAccessEvent;
import java.time.Instant;

public record SupportAccessAuditView(
        String eventId,
        String organizationId,
        String assessmentId,
        String actorUserId,
        String ticketReference,
        String reason,
        Instant occurredAt) {
    public static SupportAccessAuditView from(PlatformSupportAccessEvent event) {
        return new SupportAccessAuditView(event.id(), event.organizationId(), event.assessmentId(),
                event.actorUserId(), event.ticketReference(), event.reason(), event.occurredAt());
    }
}
