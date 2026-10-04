package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.organization.dto.AuditGapStatus;
import com.jachai.organization.dto.AuditRecoveryReport;
import com.jachai.organization.model.Membership;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.MembershipRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationAuditRecoveryServiceTests {

    private static final String ORG = "org-a";
    private static final String OTHER_ORG = "org-b";
    private static final String ADMIN = "u-platform";
    private static final Instant WHEN = Instant.parse("2026-10-04T10:15:30Z");

    @Mock MembershipRepository memberships;
    @Mock OrganizationRepository organizations;
    @Mock AuditEventRepository auditEvents;
    @Mock PlatformAdminService platform;

    OrganizationAuditRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new OrganizationAuditRecoveryService(memberships, organizations, auditEvents, platform);
        when(platform.isPlatformAdmin(ADMIN)).thenReturn(true);
        when(organizations.findById(ORG)).thenReturn(Optional.of(organization(null, null, null, null)));
        page(List.of());
    }

    private Organization organization(String transferId, String previousOwner,
                                      String transferredBy, Instant transferredAt) {
        return new Organization(ORG, "Demo Academy", OrganizationType.ACADEMY, "u-owner",
                WHEN, transferId, previousOwner, transferredBy, transferredAt);
    }

    private Membership member(String userId, String changeId, String actorId, Instant changedAt,
                              OrganizationRole previousRole, Boolean previousActive,
                              OrganizationRole role, boolean active) {
        return new Membership("m-" + userId, userId, ORG, role, active, WHEN,
                changeId, actorId, changedAt, previousRole, previousActive);
    }

    private void page(List<Membership> content) {
        page(content, content.size(), PageRequest.of(0, OrganizationAuditRecoveryService.PAGE_SIZE));
    }

    private void page(List<Membership> content, long total, Pageable pageable) {
        Page<Membership> result = new PageImpl<>(content, pageable, total);
        when(memberships.findByOrganizationId(org.mockito.ArgumentMatchers.eq(ORG), any(Pageable.class)))
                .thenReturn(result);
    }

    // -- Authorization ------------------------------------------------------------------------

    @Test
    void rejectsCallerWhoIsNotAPlatformAdmin() {
        when(platform.isPlatformAdmin("u-stranger")).thenReturn(false);
        ApiException error = assertThrows(ApiException.class,
                () -> service.report("u-stranger", ORG, 0));
        assertEquals(HttpStatus.FORBIDDEN, error.status());
        assertEquals("PLATFORM_ADMIN_REQUIRED", error.code());
    }

    @Test
    void refusesBeforeReadingAnythingWhenCallerIsNotAdmin() {
        when(platform.isPlatformAdmin("u-stranger")).thenReturn(false);
        assertThrows(ApiException.class, () -> service.report("u-stranger", ORG, 0));
        verify(organizations, never()).findById(anyString());
        verify(memberships, never()).findByOrganizationId(anyString(), any(Pageable.class));
    }

    @Test
    void rejectsAnOrganizationThatDoesNotExist() {
        when(organizations.findById("org-missing")).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class,
                () -> service.report(ADMIN, "org-missing", 0));
        assertEquals(HttpStatus.NOT_FOUND, error.status());
    }

    // -- Bounds -------------------------------------------------------------------------------

    @Test
    void rejectsANegativePage() {
        ApiException error = assertThrows(ApiException.class, () -> service.report(ADMIN, ORG, -1));
        assertEquals("INVALID_PAGE", error.code());
    }

    @Test
    void rejectsAPageBeyondTheBound() {
        ApiException error = assertThrows(ApiException.class, () -> service.report(ADMIN, ORG, 501));
        assertEquals("INVALID_PAGE", error.code());
    }

    @Test
    void reportsWhereTheScanReachedSoAPartialScanIsNotReadAsComplete() {
        page(List.of(), 120, PageRequest.of(0, OrganizationAuditRecoveryService.PAGE_SIZE));
        AuditRecoveryReport report = service.report(ADMIN, ORG, 0);
        assertEquals(120, report.membershipsScanned());
        assertTrue(report.morePages(), "a tenant larger than one page must not look fully scanned");
    }

    // -- Dry-run purity -----------------------------------------------------------------------

    @Test
    void reportingNeverWritesAnAuditEvent() {
        page(List.of(member("u-1", "c-1", "u-admin", WHEN, OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true)));
        when(auditEvents.existsById("membership:c-1")).thenReturn(false);

        AuditRecoveryReport report = service.report(ADMIN, ORG, 0);

        assertEquals(1, report.recoverableCount());
        assertFalse(report.repaired(), "a read must never present itself as a repair");
        verify(auditEvents, never()).insert(any(AuditEvent.class));
        verify(auditEvents, never()).save(any());
        verify(auditEvents, never()).deleteById(anyString());
    }

    // -- Detection ----------------------------------------------------------------------------

    @Test
    void findsAMembershipTransitionWhoseEventIsAbsent() {
        page(List.of(member("u-1", "c-1", "u-admin", WHEN, OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true)));
        when(auditEvents.existsById("membership:c-1")).thenReturn(false);

        AuditRecoveryReport report = service.report(ADMIN, ORG, 0);

        assertEquals(1, report.entries().size());
        var entry = report.entries().getFirst();
        assertEquals(AuditGapStatus.RECOVERABLE, entry.status());
        assertEquals("membership:c-1", entry.expectedEventId());
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, entry.action());
        assertEquals("u-admin", entry.actorUserId());
        assertEquals(WHEN, entry.occurredAt());
    }

    @Test
    void ignoresAMembershipWhoseEventAlreadyExists() {
        page(List.of(member("u-1", "c-1", "u-admin", WHEN, OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true)));
        when(auditEvents.existsById("membership:c-1")).thenReturn(true);
        assertTrue(service.report(ADMIN, ORG, 0).clean());
    }

    @Test
    void ignoresAMembershipThatHasNeverChanged() {
        page(List.of(new Membership("m-new", "u-new", ORG, OrganizationRole.LEARNER, true, WHEN)));
        assertTrue(service.report(ADMIN, ORG, 0).clean(),
                "a membership with no markers is not a missing event");
    }

    @Test
    void reportsPartialMarkersAsAmbiguousRatherThanGuessing() {
        // A change id survived but the previous state did not, so the transition cannot be named.
        page(List.of(member("u-1", "c-1", "u-admin", WHEN, OrganizationRole.LEARNER, null,
                OrganizationRole.INSTRUCTOR, true)));

        AuditRecoveryReport report = service.report(ADMIN, ORG, 0);

        assertEquals(0, report.recoverableCount());
        assertEquals(1, report.ambiguousCount());
        var entry = report.entries().getFirst();
        assertEquals(AuditGapStatus.AMBIGUOUS, entry.status());
        assertEquals(null, entry.action(), "an unreconstructable transition must not be named");
        verify(auditEvents, never()).existsById(anyString());
    }

    @Test
    void namesEachKindOfMembershipTransitionTheWayTheOwningServiceWould() {
        assertEquals(AuditAction.MEMBERSHIP_ADDED, OrganizationAuditRecoveryService.membershipAction(
                member("u", "c", "a", WHEN, null, false, OrganizationRole.LEARNER, true)));
        assertEquals(AuditAction.MEMBERSHIP_REACTIVATED, OrganizationAuditRecoveryService.membershipAction(
                member("u", "c", "a", WHEN, OrganizationRole.LEARNER, false, OrganizationRole.LEARNER, true)));
        assertEquals(AuditAction.MEMBERSHIP_DEACTIVATED, OrganizationAuditRecoveryService.membershipAction(
                member("u", "c", "a", WHEN, OrganizationRole.LEARNER, true, OrganizationRole.LEARNER, false)));
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, OrganizationAuditRecoveryService.membershipAction(
                member("u", "c", "a", WHEN, OrganizationRole.LEARNER, true, OrganizationRole.INSTRUCTOR, true)));
    }

    @Test
    void findsAnOwnershipTransferWhoseEventIsAbsent() {
        when(organizations.findById(ORG))
                .thenReturn(Optional.of(organization("t-1", "u-old", "u-old", WHEN)));
        when(auditEvents.existsById("ownership:t-1")).thenReturn(false);

        AuditRecoveryReport report = service.report(ADMIN, ORG, 0);

        assertEquals(1, report.recoverableCount());
        var entry = report.entries().getFirst();
        assertEquals("ORGANIZATION", entry.targetType());
        assertEquals(AuditAction.OWNERSHIP_TRANSFERRED, entry.action());
        assertEquals("ownership:t-1", entry.expectedEventId());
    }

    @Test
    void scansOwnershipOnlyOnTheFirstPageSoItIsNotCountedTwice() {
        when(organizations.findById(ORG))
                .thenReturn(Optional.of(organization("t-1", "u-old", "u-old", WHEN)));
        when(auditEvents.existsById("ownership:t-1")).thenReturn(false);
        page(List.of(), 200, PageRequest.of(1, OrganizationAuditRecoveryService.PAGE_SIZE));

        AuditRecoveryReport report = service.report(ADMIN, ORG, 1);

        assertFalse(report.ownershipScanned());
        assertTrue(report.clean(), "the ownership marker belongs to page zero only");
    }

    // -- Tenant isolation ---------------------------------------------------------------------

    @Test
    void neverReportsAMembershipBelongingToAnotherTenant() {
        Membership foreign = new Membership("m-x", "u-x", OTHER_ORG, OrganizationRole.LEARNER, true,
                WHEN, "c-x", "u-admin", WHEN, OrganizationRole.LEARNER, true);
        page(List.of(foreign));
        when(auditEvents.existsById("membership:c-x")).thenReturn(false);

        AuditRecoveryReport report = service.report(ADMIN, ORG, 0);

        assertTrue(report.clean(), "a row from another organization must never reach this report");
    }

    @Test
    void scopesTheReportToTheRequestedOrganization() {
        service.report(ADMIN, ORG, 0);
        verify(memberships).findByOrganizationId(org.mockito.ArgumentMatchers.eq(ORG), any(Pageable.class));
    }
}
