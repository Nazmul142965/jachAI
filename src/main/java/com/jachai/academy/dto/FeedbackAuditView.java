package com.jachai.academy.dto;

import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

public record FeedbackAuditView(AuditAction action, String actorUserId, Instant occurredAt) {
    public static FeedbackAuditView from(AuditEvent event) {
        return new FeedbackAuditView(event.action(), event.actorUserId(), event.occurredAt());
    }
}
