package com.jachai.talent.dto;

import com.jachai.shared.audit.AuditEvent;
import com.jachai.talent.model.ReviewDecisionType;
import java.time.Instant;

public record ReviewAuditView(String actorUserId, String attemptId,
                              ReviewDecisionType decision, String note, Instant occurredAt) {
    public static ReviewAuditView from(AuditEvent event) {
        return new ReviewAuditView(event.actorUserId(), event.details().get("attemptId"),
                ReviewDecisionType.valueOf(event.details().get("decision")),
                event.details().getOrDefault("note", ""), event.occurredAt());
    }
}
