package com.jachai.organization.dto;

import com.jachai.shared.audit.AuditAction;
import java.time.Instant;

/**
 * One provably missing — or provably unreconstructable — audit event.
 *
 * <p>{@code expectedEventId} is the deterministic id the durable markers imply. It is the same
 * id the owning service would use when it writes the event itself, which is what makes repair
 * idempotent: inserting it twice is a duplicate key, not a second history entry.
 *
 * <p>{@code action}, {@code actorUserId} and {@code occurredAt} are read from the record's own
 * markers and are null only when {@code status} is {@link AuditGapStatus#AMBIGUOUS}. Nothing in
 * this record is derived from anything other than durable state.
 */
public record AuditGapEntry(String targetType, String targetId, String expectedEventId,
                            AuditAction action, String actorUserId, Instant occurredAt,
                            AuditGapStatus status, String detail) { }
