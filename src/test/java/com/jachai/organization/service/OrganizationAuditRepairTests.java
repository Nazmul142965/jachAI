package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationAuditRepairTests {

    private static final String ORG = "org-a";
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

    private Membership member(String userId, String changeId, OrganizationRole previousRole,
                              Boolean previousActive, OrganizationRole role, boolean active) {
        return new Membership("m-" + userId, userId, ORG, role, active, WHEN,
                changeId, "u-admin", WHEN, previousRole, previousActive);
    }

    private void page(List<Membership> content) {
        when(memberships.findByOrganizationId(org.mockito.ArgumentMatchers.eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(0,
                        OrganizationAuditRecoveryService.PAGE_SIZE), content.size()));
        content.forEach(m -> when(memberships.findById(m.id())).thenReturn(Optional.of(m)));
    }

    // -- Authorization ------------------------------------------------------------------------

    @Test
    void repairRefusesACallerWhoIsNotAPlatformAdmin() {
        when(platform.isPlatformAdmin("u-stranger")).thenReturn(false);
        ApiException error = assertThrows(ApiException.class, () -> service.repair("u-stranger", ORG, 0));
        assertEquals(HttpStatus.FORBIDDEN, error.status());
        verify(auditEvents, never()).insert(any(AuditEvent.class));
    }

    @Test
    void repairHonoursThePageBound() {
        ApiException error = assertThrows(ApiException.class, () -> service.repair(ADMIN, ORG, 501));
        assertEquals("INVALID_PAGE", error.code());
        verify(auditEvents, never()).insert(any(AuditEvent.class));
    }

    // -- What it writes -----------------------------------------------------------------------

    @Test
    void writesTheEventTheMarkersImplyAndNothingElse() {
        page(List.of(member("u-1", "c-1", OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true)));
        when(auditEvents.existsById("membership:c-1")).thenReturn(false);

        AuditRecoveryReport report = service.repair(ADMIN, ORG, 0);

        ArgumentCaptor<AuditEvent> written = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(written.capture());
        AuditEvent event = written.getValue();
        assertEquals("membership:c-1", event.id());
        assertEquals(ORG, event.organizationId());
        assertEquals("u-admin", event.actorUserId(), "the actor comes from the record, never the operator");
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, event.action());
        assertEquals(WHEN, event.occurredAt(), "the time comes from the record, not from now");
        assertEquals("AUDIT_RECOVERY", event.details().get("recoveredBy"));
        assertTrue(report.repaired());
        verify(auditEvents, never()).save(any());
        verify(auditEvents, never()).deleteById(anyString());
    }

    @Test
    void neverWritesAnAmbiguousRecord() {
        page(List.of(member("u-1", "c-1", OrganizationRole.LEARNER, null,
                OrganizationRole.INSTRUCTOR, true)));

        AuditRecoveryReport report = service.repair(ADMIN, ORG, 0);

        assertEquals(1, report.ambiguousCount());
        assertEquals(AuditGapStatus.AMBIGUOUS, report.entries().getFirst().status());
        verify(auditEvents, never()).insert(any(AuditEvent.class));
    }

    @Test
    void writesTheOwnershipTransferTheOrganizationRecords() {
        when(organizations.findById(ORG))
                .thenReturn(Optional.of(organization("t-1", "u-old", "u-old", WHEN)));
        when(auditEvents.existsById("ownership:t-1")).thenReturn(false);

        service.repair(ADMIN, ORG, 0);

        ArgumentCaptor<AuditEvent> written = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(written.capture());
        assertEquals("ownership:t-1", written.getValue().id());
        assertEquals("u-old", written.getValue().details().get("previousOwnerUserId"));
    }

    // -- Idempotence --------------------------------------------------------------------------

    @Test
    void runningTwiceWritesNothingTheSecondTime() {
        Membership member = member("u-1", "c-1", OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true);
        page(List.of(member));
        when(auditEvents.existsById("membership:c-1")).thenReturn(false, true);

        service.repair(ADMIN, ORG, 0);
        AuditRecoveryReport second = service.repair(ADMIN, ORG, 0);

        verify(auditEvents, times(1)).insert(any(AuditEvent.class));
        assertTrue(second.clean(), "a repaired tenant reports nothing left to do");
    }

    @Test
    void treatsADuplicateKeyAsAlreadyRecordedRatherThanAFailure() {
        page(List.of(member("u-1", "c-1", OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true)));
        when(auditEvents.existsById("membership:c-1")).thenReturn(false);
        doThrow(new DuplicateKeyException("already recorded"))
                .when(auditEvents).insert(any(AuditEvent.class));

        AuditRecoveryReport report = service.repair(ADMIN, ORG, 0);

        assertTrue(report.entries().getFirst().detail().contains("Already recorded"));
        assertTrue(report.entries().getFirst().detail().contains("nothing was overwritten"));
    }

    // -- Concurrency --------------------------------------------------------------------------

    @Test
    void writesFromTheRecordAsItStandsNowNotAsThePageSawIt() {
        // The page was read before another operator changed the role again. Repair must record
        // the transition the membership now holds, not the stale one.
        Membership stale = member("u-1", "c-old", OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true);
        Membership fresh = member("u-1", "c-new", OrganizationRole.INSTRUCTOR, true,
                OrganizationRole.ORG_ADMIN, true);
        when(memberships.findByOrganizationId(org.mockito.ArgumentMatchers.eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stale), PageRequest.of(0,
                        OrganizationAuditRecoveryService.PAGE_SIZE), 1));
        when(memberships.findById("m-u-1")).thenReturn(Optional.of(fresh));
        when(auditEvents.existsById("membership:c-new")).thenReturn(false);

        service.repair(ADMIN, ORG, 0);

        ArgumentCaptor<AuditEvent> written = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEvents).insert(written.capture());
        assertEquals("membership:c-new", written.getValue().id());
        assertEquals(AuditAction.MEMBERSHIP_ROLE_CHANGED, written.getValue().action());
    }

    @Test
    void skipsARecordThatDisappearedBetweenTheScanAndTheWrite() {
        Membership member = member("u-1", "c-1", OrganizationRole.LEARNER, true,
                OrganizationRole.INSTRUCTOR, true);
        when(memberships.findByOrganizationId(org.mockito.ArgumentMatchers.eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(member), PageRequest.of(0,
                        OrganizationAuditRecoveryService.PAGE_SIZE), 1));
        when(memberships.findById("m-u-1")).thenReturn(Optional.empty());

        AuditRecoveryReport report = service.repair(ADMIN, ORG, 0);

        assertTrue(report.clean());
        verify(auditEvents, never()).insert(any(AuditEvent.class));
    }

    // -- Retry after partial failure ----------------------------------------------------------

    @Test
    void aFailureOnOneRecordLeavesTheEarlierWriteStandingAndIsSafeToRetry() {
        page(List.of(member("u-1", "c-1", OrganizationRole.LEARNER, true, OrganizationRole.INSTRUCTOR, true),
                member("u-2", "c-2", OrganizationRole.LEARNER, true, OrganizationRole.INSTRUCTOR, true)));
        // c-1 is absent, then recorded. c-2 stays absent until its insert finally succeeds.
        when(auditEvents.existsById("membership:c-1")).thenReturn(false, true);
        when(auditEvents.existsById("membership:c-2")).thenReturn(false, false);

        java.util.List<String> inserted = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(call -> {
            AuditEvent event = call.getArgument(0);
            if ("membership:c-2".equals(event.id()) && failOnce.getAndSet(false)) {
                throw new org.springframework.dao.DataAccessResourceFailureException("mongo unavailable");
            }
            inserted.add(event.id());
            return event;
        }).when(auditEvents).insert(any(AuditEvent.class));

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.repair(ADMIN, ORG, 0));
        assertEquals(List.of("membership:c-1"), inserted,
                "the write that succeeded before the failure is not rolled back");

        // Retrying skips what is already recorded and completes the one that failed.
        AuditRecoveryReport retry = service.repair(ADMIN, ORG, 0);

        assertEquals(List.of("membership:c-1", "membership:c-2"), inserted);
        assertEquals(1, retry.recoverableCount(), "only the record that had failed remained");
    }
}
