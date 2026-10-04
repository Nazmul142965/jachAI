package com.jachai.organization.service;

import com.jachai.organization.model.Membership;
import com.jachai.shared.audit.AuditAction;
import java.util.HashMap;
import java.util.Map;

/**
 * The single definition of how a membership's recovery markers describe a transition.
 *
 * <p>Two callers need this: {@code MembershipService}, which replays a missing event on the next
 * write to a record, and {@code OrganizationAuditRecoveryService}, which replays it on an
 * operator's instruction. They previously derived it separately.
 *
 * <p>That duplication was a real hazard rather than untidiness. If one copy changed, the operator
 * tool would write an event the owning service would never have written — not a bug in a report,
 * but a falsified entry in an audit log, indistinguishable afterwards from a genuine one. There
 * is now one implementation, so the two cannot disagree.
 */
final class MembershipAuditDerivation {

    private MembershipAuditDerivation() { }

    /** Deterministic id for the event these markers imply. Repeat inserts collide by design. */
    static String eventId(String lastChangeId) {
        return "membership:" + lastChangeId;
    }

    /**
     * True when every marker needed to describe the transition is present. A record missing any
     * of them records that something happened without recording what, and must never be rebuilt
     * from a guess.
     */
    static boolean reconstructable(Membership member) {
        return member.lastChangeId() != null && member.lastChangeActorId() != null
                && member.lastChangedAt() != null && member.previousActive() != null;
    }

    /** True when the record carries any marker at all. A membership that never changed has none. */
    static boolean hasAnyMarker(Membership member) {
        return member.lastChangeId() != null || member.lastChangeActorId() != null
                || member.lastChangedAt() != null || member.previousActive() != null;
    }

    /**
     * Names the transition. Only meaningful when {@link #reconstructable} holds; callers check
     * first, and the null-unboxing of {@code previousActive} below is what enforces that.
     */
    static AuditAction action(Membership member) {
        if (!member.previousActive()) {
            return member.previousRole() == null
                    ? AuditAction.MEMBERSHIP_ADDED : AuditAction.MEMBERSHIP_REACTIVATED;
        }
        return !member.active()
                ? AuditAction.MEMBERSHIP_DEACTIVATED : AuditAction.MEMBERSHIP_ROLE_CHANGED;
    }

    /** The details recorded alongside the event. */
    static Map<String, String> details(Membership member) {
        Map<String, String> details = new HashMap<>();
        if (member.previousRole() != null) details.put("previousRole", member.previousRole().name());
        details.put("role", member.role().name());
        return details;
    }
}
