package com.jachai.organization.service;

import com.jachai.organization.dto.AuditGapEntry;
import com.jachai.organization.dto.AuditGapStatus;
import com.jachai.organization.dto.AuditRecoveryReport;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Operator reconciliation for membership and ownership audit history.
 *
 * <p>Membership and ownership changes write the record first and its audit event second. If the
 * process dies between the two, the transition is still fully described on the record itself —
 * {@code lastChangeId}, {@code lastChangeActorId}, {@code lastChangedAt}, {@code previousRole},
 * {@code previousActive} for a membership, and the {@code ownershipTransfer*} fields for an
 * organization. {@code MembershipService} and {@code OrganizationService} replay from those
 * markers on the next write to the same record.
 *
 * <p>That self-healing is opportunistic: a membership nobody touches again never recovers, and
 * nobody can see that it is missing. This service closes that gap without changing the rule that
 * an audit event is only ever derived from durable state.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>Reporting never writes. It is a pure read, deliberately not calling the services whose
 *       own read paths replay markers as a side effect.</li>
 *   <li>Repair writes only events whose every marker is present, using the same deterministic id
 *       the owning service would use, so running it twice is a no-op.</li>
 *   <li>A record with partial markers is reported as ambiguous and never written. The actor or
 *       the previous state is genuinely unknown, and a plausible guess in an audit log is worse
 *       than an acknowledged gap.</li>
 *   <li>Nothing is updated or deleted. Repair only inserts events that are absent.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OrganizationAuditRecoveryService {

    /** One page of memberships per call. Bounded so a large tenant cannot be scanned in one request. */
    static final int PAGE_SIZE = 50;
    private static final int MAX_PAGE = 500;

    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final AuditEventRepository auditEvents;
    private final PlatformAdminService platform;

    /**
     * Dry run. Reports what repair would write, and what it would refuse to write, without
     * touching any collection.
     */
    public AuditRecoveryReport report(String actorUserId, String organizationId, int page) {
        return scan(actorUserId, organizationId, page, false);
    }

    /**
     * Writes the events the dry run called recoverable, and nothing else.
     *
     * <p>Idempotent: every event carries the deterministic id its markers imply, so a second run
     * inserts nothing. Safe under concurrent change: each record is re-read immediately before
     * its event is written, so a transition that moved on since the report is recomputed from
     * the record's current markers rather than the stale ones. Partial failure is safe to retry,
     * because each insert stands alone and an already-written event is a duplicate key rather
     * than an error.
     *
     * <p>Nothing is updated and nothing is deleted. The only write is the insert of an absent
     * event.
     */
    public AuditRecoveryReport repair(String actorUserId, String organizationId, int page) {
        return scan(actorUserId, organizationId, page, true);
    }

    private AuditRecoveryReport scan(String actorUserId, String organizationId, int page, boolean write) {
        requirePlatformAdmin(actorUserId);
        if (page < 0 || page > MAX_PAGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "Page must be between 0 and " + MAX_PAGE);
        }
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Organization not found"));

        // Sorted by id so repeated calls walk the same records in the same order. Without a
        // total order a page boundary can hide a record from every page.
        Page<Membership> members = memberships.findByOrganizationId(organizationId,
                PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));

        List<AuditGapEntry> entries = new ArrayList<>();
        for (Membership member : members.getContent()) {
            // Defence in depth: the query is already tenant-scoped, but an audit tool must not
            // be the thing that leaks one tenant's history into another's report.
            if (!organizationId.equals(member.organizationId())) continue;
            // On a repair the record is re-read, because the page may be seconds old and the
            // membership may have changed since. Writing from the stale copy would record a
            // transition that is no longer the latest one.
            Membership current = write
                    ? memberships.findById(member.id()).orElse(null)
                    : member;
            if (current == null || !organizationId.equals(current.organizationId())) continue;
            membershipGap(current)
                    .map(entry -> write ? writeMembership(current, entry) : entry)
                    .ifPresent(entries::add);
        }
        // The organization carries a single ownership marker, so it belongs to one page only.
        boolean ownershipScanned = page == 0;
        if (ownershipScanned) {
            Organization currentOrganization = write
                    ? organizations.findById(organizationId).orElse(organization)
                    : organization;
            ownershipGap(currentOrganization)
                    .map(entry -> write ? writeOwnership(currentOrganization, entry) : entry)
                    .ifPresent(entries::add);
        }

        return new AuditRecoveryReport(organizationId, Instant.now(),
                members.getNumber(), members.getTotalPages(),
                members.getTotalElements(), ownershipScanned,
                (int) entries.stream().filter(e -> e.status() == AuditGapStatus.RECOVERABLE).count(),
                (int) entries.stream().filter(e -> e.status() == AuditGapStatus.AMBIGUOUS).count(),
                write, List.copyOf(entries));
    }

    // -- Gap detection ------------------------------------------------------------------------
    // The derivation below must stay identical to MembershipService.ensureAudit and
    // OrganizationService.ensureOwnershipAudit. If they diverge, repair writes an event the
    // owning service would not have written, which is a falsified history rather than a bug.

    java.util.Optional<AuditGapEntry> membershipGap(Membership member) {
        boolean any = MembershipAuditDerivation.hasAnyMarker(member);
        // A membership that has never changed carries no markers at all. There is nothing
        // missing, so it is not a gap.
        if (!any) return java.util.Optional.empty();

        boolean complete = MembershipAuditDerivation.reconstructable(member);
        if (!complete) {
            return java.util.Optional.of(new AuditGapEntry("MEMBERSHIP", member.userId(),
                    member.lastChangeId() == null
                            ? null : MembershipAuditDerivation.eventId(member.lastChangeId()),
                    null, member.lastChangeActorId(), member.lastChangedAt(),
                    AuditGapStatus.AMBIGUOUS,
                    "A change was recorded on this membership but its markers are incomplete, "
                            + "so the actor or the previous state cannot be established. "
                            + "Resolve it from an external record; repair will not write it."));
        }

        String expectedId = MembershipAuditDerivation.eventId(member.lastChangeId());
        if (auditEvents.existsById(expectedId)) return java.util.Optional.empty();
        return java.util.Optional.of(new AuditGapEntry("MEMBERSHIP", member.userId(), expectedId,
                membershipAction(member), member.lastChangeActorId(), member.lastChangedAt(),
                AuditGapStatus.RECOVERABLE,
                "The membership records this transition; its audit event is absent."));
    }

    java.util.Optional<AuditGapEntry> ownershipGap(Organization organization) {
        boolean any = organization.ownershipTransferId() != null
                || organization.previousOwnerUserId() != null
                || organization.ownershipTransferredBy() != null
                || organization.ownershipTransferredAt() != null;
        if (!any) return java.util.Optional.empty();

        boolean complete = organization.ownershipTransferId() != null
                && organization.previousOwnerUserId() != null
                && organization.ownershipTransferredBy() != null
                && organization.ownershipTransferredAt() != null;
        if (!complete) {
            return java.util.Optional.of(new AuditGapEntry("ORGANIZATION", organization.id(),
                    organization.ownershipTransferId() == null
                            ? null : "ownership:" + organization.ownershipTransferId(),
                    null, organization.ownershipTransferredBy(), organization.ownershipTransferredAt(),
                    AuditGapStatus.AMBIGUOUS,
                    "An ownership transfer was recorded but its markers are incomplete, so the "
                            + "previous owner or the actor cannot be established. "
                            + "Resolve it from an external record; repair will not write it."));
        }

        String expectedId = "ownership:" + organization.ownershipTransferId();
        if (auditEvents.existsById(expectedId)) return java.util.Optional.empty();
        return java.util.Optional.of(new AuditGapEntry("ORGANIZATION", organization.id(), expectedId,
                AuditAction.OWNERSHIP_TRANSFERRED, organization.ownershipTransferredBy(),
                organization.ownershipTransferredAt(), AuditGapStatus.RECOVERABLE,
                "The organization records this transfer; its audit event is absent."));
    }

    // -- Repair ------------------------------------------------------------------------------

    /**
     * Inserts the event a recoverable membership marker implies. Ambiguous entries pass through
     * untouched: repair must never be the thing that invents a transition.
     */
    private AuditGapEntry writeMembership(Membership member, AuditGapEntry entry) {
        if (entry.status() != AuditGapStatus.RECOVERABLE) return entry;
        Map<String, String> details = MembershipAuditDerivation.details(member);
        details.put("recoveredBy", "AUDIT_RECOVERY");
        return insert(new AuditEvent(entry.expectedEventId(), member.organizationId(),
                member.lastChangeActorId(), entry.action(), "MEMBERSHIP", member.userId(),
                member.lastChangedAt(), details), entry);
    }

    private AuditGapEntry writeOwnership(Organization organization, AuditGapEntry entry) {
        if (entry.status() != AuditGapStatus.RECOVERABLE) return entry;
        return insert(new AuditEvent(entry.expectedEventId(), organization.id(),
                organization.ownershipTransferredBy(), AuditAction.OWNERSHIP_TRANSFERRED,
                "ORGANIZATION", organization.id(), organization.ownershipTransferredAt(),
                Map.of("previousOwnerUserId", organization.previousOwnerUserId(),
                        "ownerUserId", organization.ownerUserId(),
                        "recoveredBy", "AUDIT_RECOVERY")), entry);
    }

    /**
     * The deterministic id is what makes this idempotent. A duplicate key means another run, or
     * the owning service's own replay, already recorded this immutable transition — which is the
     * intended end state, so it is reported as written rather than as a failure.
     */
    private AuditGapEntry insert(AuditEvent event, AuditGapEntry entry) {
        try {
            auditEvents.insert(event);
        } catch (DuplicateKeyException alreadyRecorded) {
            return new AuditGapEntry(entry.targetType(), entry.targetId(), entry.expectedEventId(),
                    entry.action(), entry.actorUserId(), entry.occurredAt(), entry.status(),
                    "Already recorded by a concurrent run; nothing was overwritten.");
        }
        return new AuditGapEntry(entry.targetType(), entry.targetId(), entry.expectedEventId(),
                entry.action(), entry.actorUserId(), entry.occurredAt(), entry.status(),
                "Recovered from the record's durable markers.");
    }

    /** Delegates so report, repair and the owning service cannot disagree. */
    static AuditAction membershipAction(Membership member) {
        return MembershipAuditDerivation.action(member);
    }

    private void requirePlatformAdmin(String actorUserId) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
    }
}
