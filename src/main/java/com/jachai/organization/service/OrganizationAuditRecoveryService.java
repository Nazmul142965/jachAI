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
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
        return scan(actorUserId, organizationId, page);
    }

    private AuditRecoveryReport scan(String actorUserId, String organizationId, int page) {
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
            membershipGap(member).ifPresent(entries::add);
        }
        // The organization carries a single ownership marker, so it belongs to one page only.
        boolean ownershipScanned = page == 0;
        if (ownershipScanned) ownershipGap(organization).ifPresent(entries::add);

        return new AuditRecoveryReport(organizationId, Instant.now(),
                members.getNumber(), members.getTotalPages(),
                members.getTotalElements(), ownershipScanned,
                (int) entries.stream().filter(e -> e.status() == AuditGapStatus.RECOVERABLE).count(),
                (int) entries.stream().filter(e -> e.status() == AuditGapStatus.AMBIGUOUS).count(),
                false, List.copyOf(entries));
    }

    // -- Gap detection ------------------------------------------------------------------------
    // The derivation below must stay identical to MembershipService.ensureAudit and
    // OrganizationService.ensureOwnershipAudit. If they diverge, repair writes an event the
    // owning service would not have written, which is a falsified history rather than a bug.

    java.util.Optional<AuditGapEntry> membershipGap(Membership member) {
        boolean any = member.lastChangeId() != null || member.lastChangeActorId() != null
                || member.lastChangedAt() != null || member.previousActive() != null;
        // A membership that has never changed carries no markers at all. There is nothing
        // missing, so it is not a gap.
        if (!any) return java.util.Optional.empty();

        boolean complete = member.lastChangeId() != null && member.lastChangeActorId() != null
                && member.lastChangedAt() != null && member.previousActive() != null;
        if (!complete) {
            return java.util.Optional.of(new AuditGapEntry("MEMBERSHIP", member.userId(),
                    member.lastChangeId() == null ? null : "membership:" + member.lastChangeId(),
                    null, member.lastChangeActorId(), member.lastChangedAt(),
                    AuditGapStatus.AMBIGUOUS,
                    "A change was recorded on this membership but its markers are incomplete, "
                            + "so the actor or the previous state cannot be established. "
                            + "Resolve it from an external record; repair will not write it."));
        }

        String expectedId = "membership:" + member.lastChangeId();
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

    /** Mirrors MembershipService.ensureAudit. Only called when every marker is present. */
    static AuditAction membershipAction(Membership member) {
        if (!member.previousActive()) {
            return member.previousRole() == null
                    ? AuditAction.MEMBERSHIP_ADDED : AuditAction.MEMBERSHIP_REACTIVATED;
        }
        return !member.active()
                ? AuditAction.MEMBERSHIP_DEACTIVATED : AuditAction.MEMBERSHIP_ROLE_CHANGED;
    }

    private void requirePlatformAdmin(String actorUserId) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
    }
}
