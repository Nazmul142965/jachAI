package com.jachai.attempt.dto;

import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

/**
 * One manual grade change, as the grading history shows it.
 *
 * <p>{@code actorDisplayName} is additional to the id, never a replacement — see
 * {@link com.jachai.talent.dto.ReviewAuditView} for why both are kept. It is null when the account
 * no longer resolves.
 */
public record ManualGradeAuditView(String actorUserId, String actorDisplayName, String questionId,
                                   boolean correct, Instant gradedAt) {
    public static ManualGradeAuditView from(AuditEvent event, String actorDisplayName) {
        return new ManualGradeAuditView(event.actorUserId(), actorDisplayName,
                event.details().get("questionId"),
                Boolean.parseBoolean(event.details().get("correct")), event.occurredAt());
    }
}
