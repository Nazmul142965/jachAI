package com.jachai.billing.service;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.dto.UsageReconciliationEntry;
import com.jachai.billing.dto.UsageReconciliationReport;
import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.model.UsageReconciliationStatus;
import com.jachai.billing.model.UsageWindow;
import com.jachai.billing.repository.EntitlementUsageStore;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EntitlementUsageReconciliationService {
    private static final String CAMPAIGN_ID = "campaignId";
    private static final List<UsageMetric> MONTHLY_METRICS =
            List.of(UsageMetric.INVITATION, UsageMetric.CERTIFICATE);

    private final EntitlementUsageStore store;
    private final EntitlementService entitlements;
    private final TenantAccessService access;
    private final Duration downwardGrace;
    private final int lookbackMonths;
    private final Clock clock;

    @Autowired
    public EntitlementUsageReconciliationService(EntitlementUsageStore store,
                                                 EntitlementService entitlements,
                                                 TenantAccessService access,
                                                 @Value("${app.billing.usage-reconciliation.downward-grace:PT5M}")
                                                 Duration downwardGrace,
                                                 @Value("${app.billing.usage-reconciliation.lookback-months:15}")
                                                 int lookbackMonths) {
        this(store, entitlements, access, downwardGrace, lookbackMonths, Clock.systemUTC());
    }

    EntitlementUsageReconciliationService(EntitlementUsageStore store, EntitlementService entitlements,
                                          TenantAccessService access, Duration downwardGrace,
                                          int lookbackMonths, Clock clock) {
        if (downwardGrace.isNegative()) {
            throw new IllegalArgumentException("downwardGrace must not be negative");
        }
        if (lookbackMonths < 1) {
            throw new IllegalArgumentException("lookbackMonths must be at least 1");
        }
        this.store = store;
        this.entitlements = entitlements;
        this.access = access;
        this.downwardGrace = downwardGrace;
        this.lookbackMonths = lookbackMonths;
        this.clock = clock;
    }

    public UsageReconciliationReport report(String actorUserId, String organizationId) {
        access.requireRole(actorUserId, organizationId, OrganizationRole.ORG_ADMIN);
        return analyze(organizationId, false, actorUserId);
    }

    public UsageReconciliationReport repair(String actorUserId, String organizationId) {
        access.requireRole(actorUserId, organizationId, OrganizationRole.ORG_ADMIN);
        return analyze(organizationId, true, actorUserId);
    }

    private UsageReconciliationReport analyze(String organizationId, boolean repair, String actorUserId) {
        Instant now = clock.instant();
        PlanEntitlements plan = entitlements.plan(organizationId);
        int lookback = Math.max(1, Math.min(lookbackMonths, 13)); // cap at 13: bucket TTL is 14 months
        YearMonth current = YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC));
        Instant from = current.minusMonths(lookback - 1L).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Evidence evidence = evidence(organizationId, from);
        List<UsageWindow> candidates = candidateWindows(organizationId, current, lookback, now);
        Map<String, UsageBucket> buckets = store.buckets(organizationId,
                candidates.stream().map(UsageWindow::bucketId).toList());

        List<UsageReconciliationEntry> entries = new ArrayList<>();
        boolean repaired = false;
        for (UsageWindow window : candidates) {
            UsageBucket bucket = buckets.get(window.bucketId());
            Bounds bounds = bounds(window, evidence);
            if (bucket == null && bounds.maximum() == 0 && !isCurrent(window, current)) {
                continue;
            }
            Row row = new Row(window, bucket, bounds, plan.limitFor(window.metric()), window.closedAt(now));
            UsageReconciliationEntry entry = reconcile(organizationId, row, evidence, now, repair, actorUserId);
            entries.add(entry);
            repaired = repaired || entry.appliedUsage() != null;
        }
        if (lookbackMonths > 13) {
            for (UsageReconciliationEntry entry : entries) {
                if (entry.window().startsWith("202") && YearMonth.parse(entry.window()).isBefore(current.minusMonths(13))) {
                    // This shouldn't happen with the cap above, but guard anyway
                }
            }
        }
        return new UsageReconciliationReport(organizationId, now, plan.tier().name(),
                Math.toIntExact(downwardGrace.toSeconds()), lookback, repaired,
                List.copyOf(entries), manualSteps(entries, evidence.undatedRecords()));
    }

    private Evidence evidence(String organizationId, Instant from) {
        return new Evidence(
                store.byUtcMonth(EntitlementUsageStore.ASSIGNMENTS, organizationId, "createdAt", from, null),
                store.byUtcMonth(EntitlementUsageStore.ASSIGNMENTS, organizationId, "createdAt", from,
                        new Document(CAMPAIGN_ID, null)),
                store.byUtcMonth(EntitlementUsageStore.EMAIL_INVITATIONS, organizationId, "createdAt", from, null),
                store.byUtcMonth(EntitlementUsageStore.CERTIFICATES, organizationId, "issuedAt", from, null),
                store.undated(EntitlementUsageStore.ASSIGNMENTS, organizationId, "createdAt"),
                store.undated(EntitlementUsageStore.EMAIL_INVITATIONS, organizationId, "createdAt"),
                store.undated(EntitlementUsageStore.CERTIFICATES, organizationId, "issuedAt"),
                store.distinct(EntitlementUsageStore.ASSESSMENT_VERSIONS, organizationId, "assessmentId"),
                store.total(EntitlementUsageStore.ASSESSMENT_VERSIONS, organizationId));
    }

    private List<UsageWindow> candidateWindows(String organizationId, YearMonth current, int lookback, Instant now) {
        List<UsageWindow> candidates = new ArrayList<>();
        candidates.add(UsageWindow.ofMonth(organizationId, UsageMetric.PUBLISHED_ASSESSMENT, current));
        for (UsageMetric metric : MONTHLY_METRICS) {
            for (int back = lookback - 1; back >= 0; back--) {
                UsageWindow window = UsageWindow.ofMonth(organizationId, metric, current.minusMonths(back));
                // Skip windows whose bucket has already expired (TTL 14 months from window start)
                if (window.expiresAt() != null && !window.expiresAt().isAfter(now)) {
                    continue;
                }
                candidates.add(window);
            }
        }
        return candidates;
    }

    private static boolean isCurrent(UsageWindow window, YearMonth current) {
        return !window.monthly() || window.month().equals(current);
    }

    private Bounds bounds(UsageWindow window, Evidence evidence) {
        return switch (window.metric()) {
            case PUBLISHED_ASSESSMENT -> new Bounds(evidence.publishedAssessments(),
                    evidence.publishedVersions());
            case INVITATION -> {
                long assignments = count(evidence.assignmentsByMonth(), window.window());
                long campaignFree = Math.min(assignments,
                        count(evidence.campaignFreeAssignmentsByMonth(), window.window()));
                long minimum = campaignFree + count(evidence.emailInvitationsByMonth(), window.window());
                yield new Bounds(minimum, minimum + assignments - campaignFree);
            }
            case CERTIFICATE -> {
                long certificates = count(evidence.certificatesByMonth(), window.window());
                yield new Bounds(certificates, certificates);
            }
        };
    }

    private UsageReconciliationEntry reconcile(String organizationId, Row row, Evidence evidence,
                                               Instant now, boolean repair, String actorUserId) {
        Bounds bounds = row.bounds();
        UsageBucket bucket = row.bucket();
        try {
            if (bucket == null) {
                if (bounds.minimum() == 0) {
                    return row.entry(UsageReconciliationStatus.IN_SYNC, null,
                            "No bucket and no durable records consume this quota.");
                }
                if (!repair) {
                    return row.entry(UsageReconciliationStatus.BACKFILL_REQUIRED, null,
                            "The bucket is missing, so " + bounds.minimum()
                                    + " durable record(s) are charged against no counter.");
                }
                UsageBucket created = new UsageBucket(row.window().bucketId(), organizationId,
                        row.window().metric(), row.window().window(), bounds.minimum(), now,
                        row.window().expiresAt(), null, now, actorUserId);
                return store.insertBucket(created)
                        ? row.entry(UsageReconciliationStatus.BACKFILLED, bounds.minimum(),
                                "Created the missing bucket at the usage durable records prove.")
                        : concurrent(row, "Another writer created the bucket first, so it was not overwritten.");
            }
            long recorded = row.recorded();
            if (recorded < bounds.minimum()) {
                if (!repair) {
                    return row.entry(UsageReconciliationStatus.BACKFILL_REQUIRED, null,
                            "Recorded usage is " + (bounds.minimum() - recorded) + " below the "
                                    + bounds.minimum() + " durable record(s) that consumed a reserved unit.");
                }
                return store.replaceUsage(organizationId, row.window().bucketId(), recorded,
                                bounds.minimum(), bucket.lastChangedAt(), now, actorUserId)
                        ? row.entry(UsageReconciliationStatus.BACKFILLED, bounds.minimum(),
                                "Raised recorded usage to the durable minimum.")
                        : concurrent(row, null);
            }
            if (recorded == bounds.minimum()) {
                return row.entry(UsageReconciliationStatus.IN_SYNC, null, bounds.maximum() > bounds.minimum()
                        ? "Recorded usage equals the proven minimum; " + (bounds.maximum() - bounds.minimum())
                        + " record(s) may or may not have been charged."
                        : "Recorded usage matches the durable records for this window.");
            }
            if (recorded <= bounds.maximum()) {
                return row.entry(UsageReconciliationStatus.AMBIGUOUS, null,
                        "Recorded usage " + recorded + " is inside the range durable records prove ("
                                + bounds.minimum() + " to " + bounds.maximum() + "); "
                                + (bounds.maximum() - bounds.minimum())
                                + " record(s) cannot be classified as charged, so no change is safe.");
            }
            if (evidence.undatedRecords() > 0) {
                return row.entry(UsageReconciliationStatus.DEFERRED_UNDATED_RECORDS, null,
                        evidence.undatedRecords() + " record(s) have no usable timestamp and belong to no"
                                + " window, so a stranded unit cannot be told apart from their charge.");
            }
            // For INVITATION in past months, expired email invitations may have been deleted by TTL.
            // We cannot prove the true maximum, so defer downward repair.
            if (row.window().metric() == UsageMetric.INVITATION && row.closed()
                    && count(evidence.emailInvitationsByMonth(), row.window().window()) > 0
                    && recorded > bounds.maximum()) {
                if (!repair) {
                    return row.entry(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS, null,
                            "Recorded usage exceeds the provable maximum, but some email invitations"
                                    + " in this window may have expired and been deleted by TTL. The true"
                                    + " maximum could be higher; manual review required.");
                }
                return row.entry(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS, null,
                        "Downward repair deferred: email invitations in this closed window may have"
                                + " expired and been deleted. Manual review required.");
            }
            if (bucket.lastChangedAt() == null) {
                return row.entry(UsageReconciliationStatus.DEFERRED_NO_ACTIVITY_MARKER, null,
                        "The bucket predates reservation activity tracking, so a stranded unit cannot be"
                                + " told apart from an in-flight reservation.");
            }
            if (bucket.lastChangedAt().isAfter(now.minus(downwardGrace))) {
                return row.entry(UsageReconciliationStatus.DEFERRED_RECENT_ACTIVITY, null,
                        "The bucket changed at " + bucket.lastChangedAt()
                                + "; a reservation may still be waiting for its durable write.");
            }
            if (!repair) {
                return row.entry(UsageReconciliationStatus.STRANDED, null,
                        "Recorded usage is " + (recorded - bounds.maximum()) + " above anything durable"
                                + " records justify; releasing the difference is safe.");
            }
            return store.replaceUsage(organizationId, row.window().bucketId(), recorded, bounds.maximum(),
                            bucket.lastChangedAt(), now, actorUserId)
                    ? row.entry(UsageReconciliationStatus.STRANDED_RELEASED, bounds.maximum(),
                            "Released the stranded unit(s) down to the highest justifiable usage.")
                    : concurrent(row, null);
        } catch (RuntimeException exception) {
            log.warn("Entitlement usage reconciliation failed for bucket {}: {}", row.window().bucketId(),
                    exception.getClass().getSimpleName());
            return row.entry(UsageReconciliationStatus.DEFERRED_ERROR, null,
                    "The write failed and was skipped; re-run once the cause is cleared.");
        }
    }

    private UsageReconciliationEntry concurrent(Row row, String detail) {
        return row.entry(UsageReconciliationStatus.CONCURRENT_UPDATE, null,
                detail != null ? detail + " Re-run to reconcile it."
                        : "Usage changed while it was being reconciled, so nothing was overwritten."
                        + " Re-run to converge.");
    }

    private List<String> manualSteps(List<UsageReconciliationEntry> entries, long undatedRecords) {
        List<String> steps = new ArrayList<>();
        if (undatedRecords > 0) {
            steps.add(undatedRecords + " record(s) have no usable timestamp, so they belong to no window and"
                    + " cannot be reconciled. Backfill their timestamps, then re-run repair.");
        }
        for (UsageReconciliationEntry entry : entries) {
            String label = entry.metric() + " " + entry.window();
            switch (entry.status()) {
                case BACKFILL_REQUIRED -> steps.add(label + ": run repair to add "
                        + (entry.provenMinimumUsage() - entry.recordedUsage())
                        + " unrecorded unit(s) that durable records prove were used.");
                case STRANDED -> steps.add(label + ": " + (entry.recordedUsage() - entry.provenMaximumUsage())
                        + " stranded unit(s) can be released safely; run repair.");
                case AMBIGUOUS -> {
                    if (entry.recordedUsage() > entry.provenMinimumUsage()) {
                        steps.add(label + ": recorded usage " + entry.recordedUsage() + " cannot be verified"
                                + " because " + (entry.provenMaximumUsage() - entry.provenMinimumUsage())
                                + " record(s) may or may not have been charged."
                                + " Confirm them manually before changing the counter.");
                    }
                }
                case DEFERRED_RECENT_ACTIVITY -> steps.add(label
                        + ": a reservation may be in flight; re-run repair once the bucket has been quiet for "
                        + Math.toIntExact(downwardGrace.toSeconds()) + " seconds.");
                case DEFERRED_NO_ACTIVITY_MARKER -> steps.add(label
                        + ": the bucket predates activity tracking. Publish a new reservation and refund pair,"
                        + " or reconcile the counter by hand.");
                case DEFERRED_UNDATED_RECORDS -> steps.add(label
                        + ": stranded units stay held until every undated record has a usable timestamp.");
                case DEFERRED_EXPIRED_INVITATIONS -> steps.add(label
                        + ": some email invitations may have expired and been deleted by TTL, so the true"
                        + " maximum usage cannot be proven. Manual review required before releasing units.");
                case CONCURRENT_UPDATE, DEFERRED_ERROR -> steps.add(label
                        + ": not repaired in this run. Re-run repair to converge.");
                default -> {
                }
            }
            if (!entry.windowClosed() && entry.exceedsPlanLimit()) {
                steps.add(label + ": usage " + entry.projectedUsage() + " exceeds the plan limit "
                        + entry.planLimit() + ". New units are refused until the window resets or the plan"
                        + " changes; raise the plan deliberately rather than editing the counter.");
            }
        }
        return List.copyOf(steps);
    }

    private static long count(Map<String, Long> counts, String window) {
        return counts.getOrDefault(window, 0L);
    }

    private record Bounds(long minimum, long maximum) { }

    private record Row(UsageWindow window, UsageBucket bucket, Bounds bounds, int planLimit, boolean closed) {
        long recorded() {
            return bucket == null ? 0 : bucket.used();
        }

        UsageReconciliationEntry entry(UsageReconciliationStatus status, Long appliedUsage, String detail) {
            return new UsageReconciliationEntry(window.metric(), window.window(), window.bucketId(),
                    bucket != null, recorded(), bounds.minimum(), bounds.maximum(), planLimit, closed,
                    status, appliedUsage, detail);
        }
    }

    private record Evidence(Map<String, Long> assignmentsByMonth,
                            Map<String, Long> campaignFreeAssignmentsByMonth,
                            Map<String, Long> emailInvitationsByMonth,
                            Map<String, Long> certificatesByMonth,
                            long undatedAssignments, long undatedEmailInvitations, long undatedCertificates,
                            long publishedAssessments, long publishedVersions) {
        long undatedRecords() {
            return undatedAssignments + undatedEmailInvitations + undatedCertificates;
        }
    }
}
