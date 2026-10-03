package com.jachai.attempt.dto;

import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

public record ManualGradeAuditView(String actorUserId, String questionId, boolean correct,
                                   Instant gradedAt) {
    public static ManualGradeAuditView from(AuditEvent event) {
        return new ManualGradeAuditView(event.actorUserId(), event.details().get("questionId"),
                Boolean.parseBoolean(event.details().get("correct")), event.occurredAt());
    }
}
