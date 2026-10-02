package com.jachai.organization.dto;

import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

public record OwnershipAuditView(String previousOwnerUserId, String ownerUserId,
                                 String actorUserId, Instant occurredAt) {
    public static OwnershipAuditView from(AuditEvent event) {
        return new OwnershipAuditView(event.details().get("previousOwnerUserId"),
                event.details().get("ownerUserId"), event.actorUserId(), event.occurredAt());
    }
}
