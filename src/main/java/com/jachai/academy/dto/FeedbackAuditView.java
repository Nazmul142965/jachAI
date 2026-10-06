package com.jachai.academy.dto;

import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

/**
 * One feedback event in the history, with the actor named as well as identified.
 *
 * <p>{@code actorDisplayName} is null when the account no longer resolves; the id is always
 * present, so the row still says which account acted.
 */
public record FeedbackAuditView(AuditAction action, String actorUserId, String actorDisplayName,
                                Instant occurredAt) {
    public static FeedbackAuditView from(AuditEvent event, String actorDisplayName) {
        return new FeedbackAuditView(event.action(), event.actorUserId(), actorDisplayName,
                event.occurredAt());
    }
}
