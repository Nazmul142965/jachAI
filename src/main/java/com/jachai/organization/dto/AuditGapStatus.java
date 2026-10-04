package com.jachai.organization.dto;

/**
 * Why a recovery marker appears in an audit recovery report.
 *
 * <p>Only {@link #RECOVERABLE} may ever be written back. {@link #AMBIGUOUS} exists so that a
 * record whose transition cannot be reconstructed is surfaced to an operator rather than
 * silently skipped or, worse, guessed at.
 */
public enum AuditGapStatus {
    /**
     * Every marker needed to rebuild the event is present on the durable record, and no audit
     * event with the deterministic id those markers imply exists yet.
     */
    RECOVERABLE,

    /**
     * The record carries some transition markers but not all of them, so a change demonstrably
     * happened while the actor, the time or the previous state needed to describe it is gone.
     * Repair never touches these: reconstructing one would mean inventing a fact.
     */
    AMBIGUOUS
}
