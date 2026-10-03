package com.jachai.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.dto.UsageReconciliationEntry;
import com.jachai.billing.dto.UsageReconciliationReport;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.model.UsageReconciliationStatus;
import com.jachai.billing.repository.EntitlementUsageStore;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class EntitlementUsageReconciliationServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final int LOOKBACK = 3;

    @Mock EntitlementService entitlements;
    @Mock TenantAccessService access;

    private InMemoryUsageStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryUsageStore();
        lenient().when(entitlements.plan("org-a")).thenReturn(PlanEntitlements.forTier(PlanTier.FREE));
    }

    private EntitlementUsageReconciliationService service() {
        return new EntitlementUsageReconciliationService(store, entitlements, access, GRACE, LOOKBACK,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Instant october(int day) {
        return Instant.parse("2026-10-" + String.format("%02d", day) + "T09:00:00Z");
    }

    private static Instant september(int day) {
        return Instant.parse("2026-09-" + String.format("%02d", day) + "T09:00:00Z");
    }

    private static UsageBucket bucket(String organizationId, UsageMetric metric, String window, long used,
                                       Instant lastChangedAt) {
        Instant expiresAt = "all".equals(window) ? null
                : java.time.YearMonth.parse(window).atDay(1).plusMonths(14)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return new UsageBucket(organizationId + ":" + metric.name() + ":" + window, organizationId,
                metric, window, used, Instant.parse("2026-10-01T00:00:00Z"), expiresAt, lastChangedAt);
    }

    private UsageReconciliationEntry entry(UsageReconciliationReport report, UsageMetric metric, String window) {
        return report.entry(metric, window);
    }

    @Test
    void reportIsReadOnlyAndExposesEveryWindowInsideTheLookback() {
        store.doc(EntitlementUsageStore.ASSIGNMENTS, "org-a", october(2), false, null);
        store.doc(EntitlementUsageStore.ASSIGNMENTS, "org-a", october(3), false, null);
        store.doc(EntitlementUsageStore.EMAIL_INVITATIONS, "org-a", october(4), false, null);
        store.doc(EntitlementUsageStore.CERTIFICATES, "org-a", october(5), false, null);
        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-10", 3, october(4)));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 1, october(5)));

        UsageReconciliationReport report = service().report("admin-a", "org-a");

        assertEquals(List.of("org-a:PUBLISHED_ASSESSMENT:all", "org-a:INVITATION:2026-08",
                "org-a:INVITATION:2026-09", "org-a:INVITATION:2026-10", "org-a:CERTIFICATE:2026-08",
                "org-a:CERTIFICATE:2026-09", "org-a:CERTIFICATE:2026-10"), store.requestedBucketIds);
        assertEquals(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS,
                entry(report, UsageMetric.INVITATION, "2026-10").status());
        assertEquals(UsageReconciliationStatus.IN_SYNC, entry(report, UsageMetric.CERTIFICATE, "2026-10").status());
        assertEquals(UsageReconciliationStatus.IN_SYNC,
                entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all").status());
        assertEquals(3, report.entries().size());
        assertFalse(report.reconciled(), "surviving invitations cannot prove an exact count after TTL");
        assertFalse(report.repaired());
        assertEquals(0, store.writes);
    }

    @Test
    void unusedOlderWindowsAreNotReportedButExistingStrandedBucketsAre() {
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-08", 3, NOW.minusSeconds(86_400)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry august = entry(report, UsageMetric.CERTIFICATE, "2026-08");
        assertEquals(UsageReconciliationStatus.STRANDED_RELEASED, august.status());
        assertEquals(0, august.appliedUsage());
        assertTrue(august.windowClosed());
        assertEquals(4, report.entries().size());
        assertTrue(report.entries().stream().noneMatch(candidate -> candidate.window().equals("2026-09")));
        assertFalse(report.reconciled(), "current invitation history can be incomplete after TTL");
    }

    @Test
    void undercountedHistoryIsReportedButNotWrittenOnAReadOnlyRun() {
        store.published("org-a", 3);
        store.put(bucket("org-a", UsageMetric.PUBLISHED_ASSESSMENT, "all", 1, october(1)));

        UsageReconciliationReport report = service().report("admin-a", "org-a");

        UsageReconciliationEntry published = entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all");
        assertEquals(UsageReconciliationStatus.BACKFILL_REQUIRED, published.status());
        assertEquals(1, published.recordedUsage());
        assertEquals(3, published.provenMinimumUsage());
        assertEquals(0, store.writes, "a read-only report must not write");
        assertFalse(report.repaired());
        assertFalse(report.reconciled());
        assertTrue(report.manualSteps().stream().anyMatch(step -> step.startsWith("PUBLISHED_ASSESSMENT all")
                && step.contains("run repair")));
    }

    @Test
    void backfillIsRaisedToTheProvenMinimumAndStaysThere() {
        store.published("org-a", 3);
        store.put(bucket("org-a", UsageMetric.PUBLISHED_ASSESSMENT, "all", 1, october(1)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry published = entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all");
        assertEquals(UsageReconciliationStatus.BACKFILLED, published.status());
        assertEquals(3, published.appliedUsage());
        assertEquals(3, store.used("org-a", UsageMetric.PUBLISHED_ASSESSMENT, "all"));
        assertEquals(2L, store.revision("org-a:PUBLISHED_ASSESSMENT:all"));
        assertTrue(report.repaired());
        assertFalse(report.reconciled(), "current invitation history can be incomplete after TTL");
    }

    @Test
    void missingBucketIsCreatedFromDurableRecordsWithTheWindowExpiry() {
        store.certificate("org-a", september(4));
        store.certificate("org-a", september(5));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry created = entry(report, UsageMetric.CERTIFICATE, "2026-09");
        assertEquals(UsageReconciliationStatus.BACKFILLED, created.status());
        assertFalse(created.bucketExists());
        assertEquals(2, created.appliedUsage());
        assertEquals(Instant.parse("2027-11-01T00:00:00Z"), store.expiresAt("org-a", "2026-09"));
        assertEquals("admin-a", store.reconciledBy("org-a", "2026-09"));
    }

    @Test
    void backfillAboveThePlanLimitIsReportedInsteadOfSilentlyBlocked() {
        store.published("org-a", 5);

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry published = entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all");
        assertEquals(5, published.appliedUsage());
        assertTrue(published.exceedsPlanLimit());
        assertEquals(3, published.planLimit());
        assertTrue(report.manualSteps().stream().anyMatch(step -> step.contains("exceeds the plan limit 3")));
    }

    @Test
    void strandedReservationIsReleasedOnlyAfterTheBucketHasBeenQuiet() {
        store.certificate("org-a", october(2));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 4, NOW.minusSeconds(3600)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry released = entry(report, UsageMetric.CERTIFICATE, "2026-10");
        assertEquals(UsageReconciliationStatus.STRANDED_RELEASED, released.status());
        assertEquals(1, released.appliedUsage());
        assertEquals(1, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"));
    }

    @Test
    void reservationThatMayStillBeInFlightBlocksAWrite() {
        store.certificate("org-a", october(2));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 4, NOW.minusSeconds(30)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry held = entry(report, UsageMetric.CERTIFICATE, "2026-10");
        assertEquals(UsageReconciliationStatus.DEFERRED_RECENT_ACTIVITY, held.status());
        assertNull(held.appliedUsage());
        assertEquals(4, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"));
        assertTrue(report.manualSteps().stream()
                .anyMatch(step -> step.contains("a reservation may be in flight")));
    }

    @Test
    void bucketWithoutAnActivityMarkerIsNeverRewritten() {
        store.certificate("org-a", october(2));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 4, null));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(UsageReconciliationStatus.DEFERRED_NO_ACTIVITY_MARKER,
                entry(report, UsageMetric.CERTIFICATE, "2026-10").status());
        assertEquals(4, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"));
        assertTrue(report.manualSteps().stream()
                .anyMatch(step -> step.contains("predates activity tracking")));
    }

    @Test
    void undatedRecordsHoldStrandedUnitsAndGetTheirOwnStep() {
        store.certificate("org-a", october(2));
        store.doc(EntitlementUsageStore.ASSIGNMENTS, "org-a", null, false, null);
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 4, NOW.minusSeconds(3600)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(UsageReconciliationStatus.DEFERRED_UNDATED_RECORDS,
                entry(report, UsageMetric.CERTIFICATE, "2026-10").status());
        assertEquals(4, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"));
        assertTrue(report.manualSteps().stream()
                .anyMatch(step -> step.contains("no usable timestamp")));
    }

    @Test
    void campaignInvitationsThatCannotBeClassifiedAreReportedNotChanged() {
        store.doc(EntitlementUsageStore.EMAIL_INVITATIONS, "org-a", october(2), false, null);
        store.doc(EntitlementUsageStore.EMAIL_INVITATIONS, "org-a", october(3), false, null);
        store.doc(EntitlementUsageStore.ASSIGNMENTS, "org-a", october(3), true, null);
        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-10", 2, october(3)));

        UsageReconciliationReport verified = service().report("admin-a", "org-a");
        UsageReconciliationEntry charged = entry(verified, UsageMetric.INVITATION, "2026-10");
        assertEquals(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS, charged.status());
        assertEquals(2, charged.provenMinimumUsage());
        assertEquals(3, charged.provenMaximumUsage());
        assertFalse(verified.reconciled());

        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-10", 3, october(3)));
        UsageReconciliationReport report = service().repair("admin-a", "org-a");
        UsageReconciliationEntry unverifiable = entry(report, UsageMetric.INVITATION, "2026-10");

        assertEquals(UsageReconciliationStatus.AMBIGUOUS, unverifiable.status());
        assertEquals(3, store.used("org-a", UsageMetric.INVITATION, "2026-10"));
        assertTrue(report.manualSteps().stream()
                .anyMatch(step -> step.contains("cannot be verified")));
    }

    @Test
    void directCampaignInvitationsAreCountedAsChargedAndProven() {
        store.campaignAssignment("org-a", october(3));
        store.campaignAssignment("org-a", october(4));
        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-10", 2, october(4)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry invitations = entry(report, UsageMetric.INVITATION, "2026-10");
        assertEquals(0, invitations.provenMinimumUsage());
        assertEquals(2, invitations.provenMaximumUsage());
        assertEquals(UsageReconciliationStatus.AMBIGUOUS, invitations.status());
    }

    @Test
    void currentMonthInvitationWithZeroSurvivorsIsNeverDecremented() {
        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-10", 4, NOW.minusSeconds(3600)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry invitation = entry(report, UsageMetric.INVITATION, "2026-10");
        assertEquals(0, invitation.provenMaximumUsage());
        assertEquals(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS, invitation.status());
        assertNull(invitation.appliedUsage());
        assertEquals(4, store.used("org-a", UsageMetric.INVITATION, "2026-10"));
        assertFalse(report.reconciled());
    }

    @Test
    void closedMonthInvitationWithZeroSurvivorsIsNeverDecremented() {
        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-09", 4, NOW.minusSeconds(86_400)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry invitation = entry(report, UsageMetric.INVITATION, "2026-09");
        assertTrue(invitation.windowClosed());
        assertEquals(0, invitation.provenMaximumUsage());
        assertEquals(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS, invitation.status());
        assertNull(invitation.appliedUsage());
        assertEquals(4, store.used("org-a", UsageMetric.INVITATION, "2026-09"));
    }

    @Test
    void expiredInvitationDeferralTakesPrecedenceOverUndatedRecords() {
        store.put(bucket("org-a", UsageMetric.INVITATION, "2026-10", 4, NOW.minusSeconds(3600)));
        store.doc(EntitlementUsageStore.ASSIGNMENTS, "org-a", null, false, null);

        UsageReconciliationEntry invitation = entry(service().repair("admin-a", "org-a"),
                UsageMetric.INVITATION, "2026-10");

        assertEquals(UsageReconciliationStatus.DEFERRED_EXPIRED_INVITATIONS, invitation.status());
        assertEquals(4, store.used("org-a", UsageMetric.INVITATION, "2026-10"));
    }

    @Test
    void aReservationLandingDuringRepairIsNeverOverwritten() {
        store.certificate("org-a", october(2));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 4, NOW.minusSeconds(3600)));
        store.onWrite = () -> store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 5, NOW));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(UsageReconciliationStatus.CONCURRENT_UPDATE,
                entry(report, UsageMetric.CERTIFICATE, "2026-10").status());
        assertEquals(5, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"),
                "the concurrent reservation must survive the repair");
        assertTrue(report.manualSteps().stream().anyMatch(step -> step.contains("Re-run repair")));
    }

    @Test
    void sameMillisecondReserveRefundAbaCannotPassRevisionCompareAndSet() {
        Instant persistedMillisecond = Instant.parse("2026-10-20T11:00:00.000Z");
        UsageBucket observed = bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 4,
                persistedMillisecond);
        store.certificate("org-a", october(2));
        store.put(observed);
        store.onWrite = () -> store.put(new UsageBucket(observed.id(), observed.organizationId(),
                observed.metric(), observed.window(), observed.used(), observed.createdAt(),
                observed.expiresAt(), persistedMillisecond, observed.lastReconciledAt(),
                observed.reconciledByUserId(), observed.revision() + 2));

        UsageReconciliationEntry result = entry(service().repair("admin-a", "org-a"),
                UsageMetric.CERTIFICATE, "2026-10");

        assertEquals(UsageReconciliationStatus.CONCURRENT_UPDATE, result.status());
        assertEquals(4, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"));
        assertEquals(3L, store.revision(observed.id()));
    }

    @Test
    void aFailedWriteIsReportedAndTheOtherWindowsAreStillReconciled() {
        store.certificate("org-a", october(2));
        store.certificate("org-a", october(3));
        store.published("org-a", 2);
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 1, october(3)));
        store.failWritesFor = "org-a:CERTIFICATE:2026-10";

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(UsageReconciliationStatus.DEFERRED_ERROR,
                entry(report, UsageMetric.CERTIFICATE, "2026-10").status());
        assertEquals(1, store.used("org-a", UsageMetric.CERTIFICATE, "2026-10"));
        assertEquals(UsageReconciliationStatus.BACKFILLED,
                entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all").status());
        assertEquals(2, store.used("org-a", UsageMetric.PUBLISHED_ASSESSMENT, "all"));

        store.failWritesFor = null;
        UsageReconciliationReport retried = service().repair("admin-a", "org-a");
        assertEquals(UsageReconciliationStatus.BACKFILLED,
                entry(retried, UsageMetric.CERTIFICATE, "2026-10").status());
        assertFalse(retried.reconciled(), "current invitation history can be incomplete after TTL");
    }

    @Test
    void repeatedRepairIsIdempotentAndConvergesToNoManualSteps() {
        store.published("org-a", 2);
        store.certificate("org-a", october(2));
        store.certificate("org-a", october(3));
        store.certificate("org-a", october(4));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 9, NOW.minusSeconds(3600)));

        EntitlementUsageReconciliationService service = service();
        UsageReconciliationReport first = service.repair("admin-a", "org-a");
        int writesAfterFirstRun = store.writes;
        UsageReconciliationReport second = service.repair("admin-a", "org-a");

        assertTrue(first.repaired());
        assertFalse(first.reconciled(), "current invitation history can be incomplete after TTL");
        assertEquals(writesAfterFirstRun, store.writes, "a second run must not write again");
        assertFalse(second.repaired());
        assertFalse(second.reconciled(), "current invitation history can be incomplete after TTL");
        assertEquals(UsageReconciliationStatus.IN_SYNC,
                entry(second, UsageMetric.CERTIFICATE, "2026-10").status());
    }

    @Test
    void monthBoundariesSeparateWindowsAndClosedWindowsDoNotBlockQuota() {
        for (int index = 0; index < 35; index++) {
            store.certificate("org-a", Instant.parse("2026-09-30T23:59:59Z"));
        }
        for (int index = 0; index < 34; index++) {
            store.certificate("org-a", Instant.parse("2026-10-01T00:00:00Z"));
        }
        store.certificate("org-a", october(20));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-09", 40, NOW.minusSeconds(86_400)));
        store.put(bucket("org-a", UsageMetric.CERTIFICATE, "2026-10", 1, october(20)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        UsageReconciliationEntry septemberWindow = entry(report, UsageMetric.CERTIFICATE, "2026-09");
        assertEquals(35, septemberWindow.provenMinimumUsage());
        assertTrue(septemberWindow.windowClosed());
        assertEquals(UsageReconciliationStatus.STRANDED_RELEASED, septemberWindow.status());
        assertTrue(septemberWindow.exceedsPlanLimit());
        assertTrue(report.manualSteps().stream().noneMatch(step -> step.startsWith("CERTIFICATE 2026-09")
                && step.contains("exceeds the plan limit")));

        UsageReconciliationEntry octoberWindow = entry(report, UsageMetric.CERTIFICATE, "2026-10");
        assertEquals(35, octoberWindow.provenMinimumUsage());
        assertEquals(octoberWindow.provenMinimumUsage(), octoberWindow.provenMaximumUsage());
        assertFalse(octoberWindow.windowClosed());
        assertEquals(UsageReconciliationStatus.BACKFILLED, octoberWindow.status());
        assertTrue(report.manualSteps().stream().anyMatch(step -> step.startsWith("CERTIFICATE 2026-10")
                && step.contains("exceeds the plan limit 30")));
    }

    @Test
    void windowsOlderThanTheLookbackAreNotInspected() {
        store.certificate("org-a", Instant.parse("2026-06-30T23:59:59Z"));
        store.certificate("org-a", october(2));

        UsageReconciliationReport report = service().report("admin-a", "org-a");

        assertEquals(3, report.entries().size());
        assertTrue(report.manualSteps().stream().noneMatch(step -> step.contains("2026-06")));
    }

    @Test
    void anotherTenantsUsageIsNeitherReadNorWritten() {
        store.published("org-a", 1);
        store.published("org-b", 7);
        store.put(bucket("org-b", UsageMetric.PUBLISHED_ASSESSMENT, "all", 7, october(1)));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(1, entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all").appliedUsage());
        assertEquals(7, store.used("org-b", UsageMetric.PUBLISHED_ASSESSMENT, "all"));
        assertTrue(store.organizationsSeen().stream().allMatch(id -> id.equals("org-a")));
    }

    @Test
    void bucketsAreReadByTheirOwnTenantIdEvenWhenTheIdLooksForeign() {
        store.put(bucket("org-b", UsageMetric.PUBLISHED_ASSESSMENT, "all", 4, october(1)));
        store.published("org-a", 2);

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(UsageReconciliationStatus.BACKFILLED,
                entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all").status());
        assertEquals(4, store.used("org-b", UsageMetric.PUBLISHED_ASSESSMENT, "all"));
    }

    @Test
    void anExistingBucketCreatedConcurrentlyIsNotOverwritten() {
        store.published("org-a", 2);
        store.onWrite = () -> store.put(bucket("org-a", UsageMetric.PUBLISHED_ASSESSMENT, "all", 1, NOW));

        UsageReconciliationReport report = service().repair("admin-a", "org-a");

        assertEquals(UsageReconciliationStatus.CONCURRENT_UPDATE,
                entry(report, UsageMetric.PUBLISHED_ASSESSMENT, "all").status());
        assertEquals(1, store.used("org-a", UsageMetric.PUBLISHED_ASSESSMENT, "all"));
    }

    @Test
    void bothEndpointsRequireAnOrganizationAdmin() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient organization permission"))
                .when(access).requireRole(any(), any(), any());

        ApiException reportFailure = assertThrows(ApiException.class,
                () -> service().report("member-a", "org-a"));
        ApiException repairFailure = assertThrows(ApiException.class,
                () -> service().repair("member-a", "org-a"));

        assertEquals(HttpStatus.FORBIDDEN, reportFailure.status());
        assertEquals(HttpStatus.FORBIDDEN, repairFailure.status());
        verify(access, times(2)).requireRole(eq("member-a"), eq("org-a"),
                eq(com.jachai.organization.model.OrganizationRole.ORG_ADMIN));
        assertEquals(0, store.organizationsSeen().size());
    }

    @Test
    void reportPublishesTheGracePeriodAndLookbackUsedForTheDecision() {
        UsageReconciliationReport report = service().report("admin-a", "org-a");

        assertEquals(300, report.downwardGraceSeconds());
        assertEquals(LOOKBACK, report.lookbackMonths());
        assertEquals("FREE", report.tier());
        assertEquals(NOW, report.generatedAt());
        assertEquals("org-a", report.organizationId());
    }

    @Test
    void anInvalidGraceOrLookbackFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new EntitlementUsageReconciliationService(
                store, entitlements, access, Duration.ofSeconds(-1), LOOKBACK, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new EntitlementUsageReconciliationService(
                store, entitlements, access, GRACE, 0, Clock.systemUTC()));
    }

    static final class InMemoryUsageStore implements EntitlementUsageStore {
        private final List<Doc> docs = new ArrayList<>();
        private final Map<String, UsageBucket> byId = new HashMap<>();
        private final Map<String, List<String>> organizations = new HashMap<>();
        private List<String> requestedBucketIds = List.of();
        private int writes;
        private String failWritesFor;
        private Runnable onWrite;

        void doc(String collection, String organizationId, Instant date, boolean campaign, String assessmentId) {
            docs.add(new Doc(collection, organizationId, date, campaign, assessmentId));
        }

        void assignment(String organizationId, Instant date) {
            doc(ASSIGNMENTS, organizationId, date, false, null);
        }

        void campaignAssignment(String organizationId, Instant date) {
            doc(ASSIGNMENTS, organizationId, date, true, null);
        }

        void invitation(String organizationId, Instant date) {
            doc(EMAIL_INVITATIONS, organizationId, date, false, null);
        }

        void certificate(String organizationId, Instant date) {
            doc(CERTIFICATES, organizationId, date, false, null);
        }

        void published(String organizationId, int assessments) {
            for (int index = 1; index <= assessments; index++) {
                doc(ASSESSMENT_VERSIONS, organizationId, october(1), false, "assessment-" + index);
            }
        }

        void put(UsageBucket bucket) {
            byId.put(bucket.id(), bucket);
        }

        void replace(UsageBucket bucket) {
            put(bucket);
        }

        long used(String organizationId, UsageMetric metric, String window) {
            UsageBucket found = byId.get(organizationId + ":" + metric.name() + ":" + window);
            return found == null ? 0 : found.used();
        }

        Long revision(String bucketId) {
            return byId.get(bucketId).revision();
        }

        Instant expiresAt(String organizationId, String window) {
            return byId.get(organizationId + ":CERTIFICATE:" + window).expiresAt();
        }

        String reconciledBy(String organizationId, String window) {
            return byId.get(organizationId + ":CERTIFICATE:" + window).reconciledByUserId();
        }

        List<String> organizationsSeen() {
            return organizations.getOrDefault("any", List.of());
        }

        @Override
        public Map<String, UsageBucket> buckets(String organizationId, Collection<String> bucketIds) {
            organizations.computeIfAbsent("any", key -> new ArrayList<>()).add(organizationId);
            requestedBucketIds = List.copyOf(bucketIds);
            Map<String, UsageBucket> found = new LinkedHashMap<>();
            for (String id : bucketIds) {
                UsageBucket bucket = byId.get(id);
                if (bucket != null && bucket.organizationId().equals(organizationId)) {
                    found.put(id, bucket);
                }
            }
            return found;
        }

        @Override
        public boolean insertBucket(UsageBucket bucket) {
            writes++;
            runWriteHook();
            if (failWritesFor != null && failWritesFor.equals(bucket.id())) {
                throw new IllegalStateException("write failed");
            }
            if (byId.containsKey(bucket.id())) {
                return false;
            }
            byId.put(bucket.id(), bucket);
            return true;
        }

        @Override
        public boolean replaceUsage(String organizationId, String bucketId, long expectedUsage, long newUsage,
                                    Long expectedRevision, Instant reconciledAt, String actorUserId) {
            writes++;
            runWriteHook();
            if (failWritesFor != null && failWritesFor.equals(bucketId)) {
                throw new IllegalStateException("write failed");
            }
            UsageBucket bucket = byId.get(bucketId);
            if (bucket == null || !bucket.organizationId().equals(organizationId)
                    || bucket.used() != expectedUsage
                    || !Objects.equals(bucket.revision(), expectedRevision)) {
                return false;
            }
            byId.put(bucketId, new UsageBucket(bucketId, organizationId, bucket.metric(), bucket.window(),
                    newUsage, bucket.createdAt(), bucket.expiresAt(), bucket.lastChangedAt(),
                    reconciledAt, actorUserId, expectedRevision == null ? 1L : expectedRevision + 1L));
            return true;
        }

        @Override
        public long total(String collection, String organizationId) {
            return docs.stream()
                    .filter(doc -> doc.collection.equals(collection) && doc.organizationId.equals(organizationId))
                    .count();
        }

        @Override
        public long distinct(String collection, String organizationId, String field) {
            return docs.stream()
                    .filter(doc -> doc.collection.equals(collection) && doc.organizationId.equals(organizationId))
                    .map(Doc::assessmentId)
                    .distinct()
                    .count();
        }

        @Override
        public long undated(String collection, String organizationId, String dateField) {
            return docs.stream()
                    .filter(doc -> doc.collection.equals(collection) && doc.organizationId.equals(organizationId)
                            && doc.date == null)
                    .count();
        }

        @Override
        public Map<String, Long> byUtcMonth(String collection, String organizationId, String dateField,
                                            Instant fromInclusive, Document extraMatch) {
            boolean campaignFree = extraMatch != null && extraMatch.containsKey("campaignId");
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Doc doc : docs) {
                if (!doc.collection.equals(collection) || !doc.organizationId.equals(organizationId)
                        || doc.date == null || doc.date.isBefore(fromInclusive)
                        || (campaignFree && doc.campaign)) {
                    continue;
                }
                String month = java.time.YearMonth.from(
                        java.time.LocalDate.ofInstant(doc.date, ZoneOffset.UTC)).toString();
                counts.merge(month, 1L, Long::sum);
            }
            return counts;
        }

        private void runWriteHook() {
            if (onWrite != null) {
                Runnable hook = onWrite;
                onWrite = null;
                hook.run();
            }
        }

        private record Doc(String collection, String organizationId, Instant date, boolean campaign,
                           String assessmentId) { }
    }
}
