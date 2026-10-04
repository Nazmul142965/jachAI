package com.jachai.organization.dto;

import java.time.Instant;
import java.util.List;

/**
 * A bounded, tenant-scoped view of audit events that durable records prove should exist.
 *
 * <p>The report is one page of memberships plus, on the first page only, the organization's own
 * ownership marker. {@code membershipPage} and {@code membershipTotalPages} say where the scan
 * reached, because a report covering part of a tenant must not read as covering all of it.
 *
 * <p>{@code repaired} is false for a read. The repair operation returns this same shape with
 * {@code repaired} true and the entries it actually wrote, so an operator compares like with
 * like rather than reading two different report formats.
 */
public record AuditRecoveryReport(String organizationId, Instant generatedAt,
                                  int membershipPage, int membershipTotalPages,
                                  long membershipsScanned, boolean ownershipScanned,
                                  int recoverableCount, int ambiguousCount,
                                  boolean repaired, List<AuditGapEntry> entries) {

    /** True when this page found nothing an operator needs to act on. */
    public boolean clean() {
        return recoverableCount == 0 && ambiguousCount == 0;
    }

    /** True when later pages remain unscanned, so the tenant is not yet proven clean. */
    public boolean morePages() {
        return membershipPage + 1 < membershipTotalPages;
    }
}
