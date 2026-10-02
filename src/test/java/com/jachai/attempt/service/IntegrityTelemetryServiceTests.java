package com.jachai.attempt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.attempt.dto.RecordTelemetryRequest;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.model.TelemetryEvent;
import com.jachai.attempt.model.TelemetryEventType;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.attempt.repository.TelemetryEventRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrityTelemetryServiceTests {
    @Mock TelemetryEventRepository events;
    @Mock AttemptRepository attempts;
    @Mock AssignmentRepository assignments;
    @Mock OrganizationRepository organizations;
    @Mock TenantAccessService access;

    private IntegrityTelemetryService service() {
        return new IntegrityTelemetryService(events, attempts, assignments, organizations, access);
    }

    private void talent() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(
                new Organization("org-a", "Talent", OrganizationType.TALENT, "owner", Instant.now())));
    }

    private Attempt attempt(AttemptStatus status) {
        return new Attempt("attempt", "org-a", "assignment", "version", "candidate", 1,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(600), status,
                Map.of(), status == AttemptStatus.SUBMITTED ? Instant.now() : null,
                null, null, null, 1L);
    }

    private Assignment assignment(String campaignId) {
        return new Assignment("assignment", "org-a", "assessment", "version", campaignId,
                null, "candidate", 1, Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(600), Instant.now());
    }

    @Test
    void candidateCanRecordOnlyOwnActiveCampaignAttempt() {
        talent();
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "candidate"))
                .thenReturn(Optional.of(attempt(AttemptStatus.IN_PROGRESS)));
        when(assignments.findByIdAndOrganizationId("assignment", "org-a"))
                .thenReturn(Optional.of(assignment("campaign")));
        when(events.insert(any(TelemetryEvent.class))).thenAnswer(call -> call.getArgument(0));

        var result = service().record("candidate", "org-a", "attempt",
                new RecordTelemetryRequest(TelemetryEventType.ANSWER_SAVED, "question"));

        assertEquals(TelemetryEventType.ANSWER_SAVED, result.type());
        verify(access).requireRole("candidate", "org-a", OrganizationRole.CANDIDATE);
        verify(events).insert(any(TelemetryEvent.class));
    }

    @Test
    void anotherCandidateCannotRecordAnEvent() {
        talent();
        ApiException error = assertThrows(ApiException.class, () -> service().record(
                "intruder", "org-a", "attempt",
                new RecordTelemetryRequest(TelemetryEventType.TAB_HIDDEN, null)));
        assertEquals("NOT_FOUND", error.code());
        verify(events, never()).insert(any(TelemetryEvent.class));
    }

    @Test
    void closedAttemptRejectsEvents() {
        talent();
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "candidate"))
                .thenReturn(Optional.of(attempt(AttemptStatus.SUBMITTED)));
        when(assignments.findByIdAndOrganizationId("assignment", "org-a"))
                .thenReturn(Optional.of(assignment("campaign")));
        ApiException error = assertThrows(ApiException.class, () -> service().record(
                "candidate", "org-a", "attempt",
                new RecordTelemetryRequest(TelemetryEventType.TAB_HIDDEN, null)));
        assertEquals("ATTEMPT_CLOSED", error.code());
    }

    @Test
    void academyAssignmentCannotBecomeTalentTelemetry() {
        talent();
        when(attempts.findByIdAndOrganizationIdAndUserId("attempt", "org-a", "candidate"))
                .thenReturn(Optional.of(attempt(AttemptStatus.IN_PROGRESS)));
        when(assignments.findByIdAndOrganizationId("assignment", "org-a"))
                .thenReturn(Optional.of(assignment(null)));
        ApiException error = assertThrows(ApiException.class, () -> service().record(
                "candidate", "org-a", "attempt",
                new RecordTelemetryRequest(TelemetryEventType.TAB_HIDDEN, null)));
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void reviewerSeesUntrustedSignalsNotAnAutomaticVerdict() {
        talent();
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(attempt(AttemptStatus.SUBMITTED)));
        when(assignments.findByIdAndOrganizationId("assignment", "org-a"))
                .thenReturn(Optional.of(assignment("campaign")));
        Instant now = Instant.now();
        when(events.findByOrganizationIdAndAttemptIdAndExpiresAtAfterOrderByReceivedAtAsc(
                org.mockito.ArgumentMatchers.eq("org-a"), org.mockito.ArgumentMatchers.eq("attempt"), any(Instant.class)))
                .thenReturn(List.of(
                        new TelemetryEvent("1", "org-a", "attempt", "candidate", TelemetryEventType.ANSWER_SAVED,
                                "q1", now, now.plusSeconds(3600)),
                        new TelemetryEvent("2", "org-a", "attempt", "candidate", TelemetryEventType.ANSWER_SAVED,
                                "q2", now.plusMillis(700), now.plusSeconds(3600)),
                        new TelemetryEvent("3", "org-a", "attempt", "candidate", TelemetryEventType.TAB_HIDDEN,
                                null, now.plusSeconds(2), now.plusSeconds(3600))));

        var report = service().report("recruiter", "org-a", "attempt");

        assertEquals(3, report.totalEvents());
        assertEquals(1, report.clientReportedRapidAnswerIntervals());
        assertEquals(1L, report.eventCounts().get(TelemetryEventType.TAB_HIDDEN));
        assertTrue(report.interpretation().contains("not proof"));
        verify(access).requireRole("recruiter", "org-a", OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
    }

    @Test
    void reviewerCannotReadAttemptFromAnotherTenant() {
        talent();
        ApiException error = assertThrows(ApiException.class,
                () -> service().report("recruiter", "org-a", "other-attempt"));
        assertEquals("NOT_FOUND", error.code());
        verify(events, never()).findByOrganizationIdAndAttemptIdAndExpiresAtAfterOrderByReceivedAtAsc(
                any(), any(), any());
    }
}
