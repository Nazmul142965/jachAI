package com.jachai.talent.dto;

import com.jachai.shared.audit.AuditEvent;
import com.jachai.talent.model.ReviewDecisionType;
import java.time.Instant;

/**
 * One recorded hiring decision, as the review history shows it.
 *
 * <p>{@code actorDisplayName} is additional to the id, never a replacement. An audit trail answers
 * who decided this, and a reader cannot answer that from a user id; but the id is what the record
 * is actually keyed on and two reviewers can share a name, so both are kept. The name can be null
 * when the account no longer resolves, which is exactly when an old audit row is most likely to be
 * read.
 */
public record ReviewAuditView(String actorUserId, String actorDisplayName, String attemptId,
                              ReviewDecisionType decision, String note, Instant occurredAt) {
    public static ReviewAuditView from(AuditEvent event, String actorDisplayName) {
        return new ReviewAuditView(event.actorUserId(), actorDisplayName,
                event.details().get("attemptId"),
                ReviewDecisionType.valueOf(event.details().get("decision")),
                event.details().getOrDefault("note", ""), event.occurredAt());
    }
}
