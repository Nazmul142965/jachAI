package com.jachai.talent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.billing.service.EntitlementService;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.attempt.dto.CreateAssignmentRequest;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.talent.dto.CreateCampaignRequest;
import com.jachai.talent.dto.ReviewRequest;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.model.ReviewDecision;
import com.jachai.talent.model.ReviewDecisionType;
import com.jachai.talent.repository.HiringCampaignRepository;
import com.jachai.talent.repository.ReviewDecisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class TalentCampaignServiceTests {
    @Mock HiringCampaignRepository campaigns;
    @Mock ReviewDecisionRepository decisions;
    @Mock AssessmentRepository assessments;
    @Mock AssignmentRepository assignments;
    @Mock AttemptRepository attempts;
    @Mock OrganizationRepository organizations;
    @Mock TenantAccessService access;
    @Mock EntitlementService entitlements;
    @Mock AuditEventRepository auditEvents;

    private TalentCampaignService service() {
        return new TalentCampaignService(campaigns, decisions, assessments, assignments,
                attempts, organizations, access, entitlements, auditEvents);
    }

    private Organization talent() {
        return new Organization("org-a", "Talent org", OrganizationType.TALENT, "owner", Instant.now());
    }

    private HiringCampaign campaign(boolean open) {
        return new HiringCampaign("campaign", "org-a", "assessment", "assessment:2",
                "Backend Engineer", "recruiter", open, Instant.now(), 1L);
    }

    private Assignment assignment() {
        return new Assignment("assignment", "org-a", "assessment", "assessment:2", "campaign", null,
                "candidate", 2, Instant.now().minusSeconds(60), Instant.now().plusSeconds(900), Instant.now());
    }

    private Attempt attempt(int number, int percentage) {
        return new Attempt("attempt-" + number, "org-a", "assignment", "assessment:2", "candidate", number,
                Instant.now().minusSeconds(90), Instant.now().minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), percentage, percentage >= 60, Instant.now(), 1L);
    }

    @Test
    void campaignFreezesPublishedVersionFromSameTenant() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(assessments.findByIdAndOrganizationId("assessment", "org-a")).thenReturn(Optional.of(
                new Assessment("assessment", "org-a", "recruiter", "Java", 30, 60,
                        List.of("q1"), ResultReleasePolicy.MANUAL, AssessmentStatus.PUBLISHED,
                        2, Instant.now(), 1L)));
        when(campaigns.insert(any(HiringCampaign.class))).thenAnswer(call -> call.getArgument(0));

        var result = service().create("recruiter", "org-a", new CreateCampaignRequest("Backend Engineer", "assessment"));

        assertEquals("assessment:2", result.assessmentVersionId());
        ArgumentCaptor<HiringCampaign> saved = ArgumentCaptor.forClass(HiringCampaign.class);
        verify(campaigns).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
    }

    @Test
    void campaignCannotUseAnotherTenantsAssessment() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));

        ApiException error = assertThrows(ApiException.class, () -> service().create(
                "recruiter", "org-a", new CreateCampaignRequest("Backend", "other-assessment")));

        assertEquals("NOT_FOUND", error.code());
        verify(assessments).findByIdAndOrganizationId("other-assessment", "org-a");
        verify(campaigns, never()).insert(any(HiringCampaign.class));
    }

    @Test
    void academyOrganizationCannotCreateHiringCampaign() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Academy", OrganizationType.ACADEMY, "owner", Instant.now())));

        assertEquals("TALENT_ONLY", assertThrows(ApiException.class, () -> service().create(
                "recruiter", "org-a", new CreateCampaignRequest("Backend", "assessment"))).code());
        verify(assessments, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void invitationUsesCampaignVersionAndRejectsDuplicateCandidate() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(true)));
        when(assignments.insert(any(Assignment.class))).thenAnswer(call -> call.getArgument(0))
                .thenThrow(new DuplicateKeyException("duplicate"));
        Instant now = Instant.now();
        var request = new CreateAssignmentRequest("candidate", 2, now, now.plusSeconds(3600));

        var invited = service().invite("recruiter", "org-a", "campaign", request);

        assertEquals("assessment:2", invited.assessmentVersionId());
        assertEquals("campaign", invited.campaignId());
        verify(access).requireRole("candidate", "org-a", OrganizationRole.CANDIDATE);
        assertEquals("ALREADY_INVITED", assertThrows(ApiException.class,
                () -> service().invite("recruiter", "org-a", "campaign", request)).code());
    }

    @Test
    void closedCampaignRejectsNewInvitation() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(false)));
        Instant now = Instant.now();

        assertEquals("CAMPAIGN_CLOSED", assertThrows(ApiException.class, () -> service().invite(
                "recruiter", "org-a", "campaign",
                new CreateAssignmentRequest("candidate", 1, now, now.plusSeconds(3600)))).code());
        verify(assignments, never()).insert(any(Assignment.class));
    }

    @Test
    void comparisonUsesLatestSubmittedAttemptAndTenantScopedReview() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(true)));
        when(assignments.findByOrganizationIdAndCampaignId("org-a", "campaign", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(assignment()), PageRequest.of(0, 20), 1));
        when(attempts.findByOrganizationIdAndAssignmentIdInAndStatus("org-a", List.of("assignment"),
                AttemptStatus.SUBMITTED)).thenReturn(List.of(attempt(1, 40), attempt(2, 80)));
        when(decisions.findByOrganizationIdAndCampaignIdAndCandidateUserIdIn(
                "org-a", "campaign", List.of("candidate"))).thenReturn(List.of(new ReviewDecision(
                "campaign:candidate", "org-a", "campaign", "candidate", "attempt-2",
                ReviewDecisionType.SHORTLISTED, "Strong Java", "recruiter", Instant.now(), 1L)));

        var result = service().compare("recruiter", "org-a", "campaign", PageRequest.of(0, 20));

        assertEquals(80, result.getContent().getFirst().percentage());
        assertEquals("attempt-2", result.getContent().getFirst().attemptId());
        assertEquals(ReviewDecisionType.SHORTLISTED, result.getContent().getFirst().decision());
    }

    @Test
    void priorAttemptReviewDoesNotCarryOverToNewerAttempt() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(true)));
        when(assignments.findByOrganizationIdAndCampaignId("org-a", "campaign", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(assignment()), PageRequest.of(0, 20), 1));
        when(attempts.findByOrganizationIdAndAssignmentIdInAndStatus("org-a", List.of("assignment"),
                AttemptStatus.SUBMITTED)).thenReturn(List.of(attempt(1, 40), attempt(2, 80)));
        when(decisions.findByOrganizationIdAndCampaignIdAndCandidateUserIdIn(
                "org-a", "campaign", List.of("candidate"))).thenReturn(List.of(new ReviewDecision(
                "campaign:candidate", "org-a", "campaign", "candidate", "attempt-1",
                ReviewDecisionType.REJECTED, "Old attempt", "recruiter", Instant.now(), 1L)));

        var result = service().compare("recruiter", "org-a", "campaign", PageRequest.of(0, 20));

        assertEquals(80, result.getContent().getFirst().percentage());
        assertNull(result.getContent().getFirst().decision());
        assertNull(result.getContent().getFirst().reviewerNote());
    }

    @Test
    void reviewCannotUseAttemptFromAnotherCampaign() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(true)));
        when(attempts.findByIdAndOrganizationId("attempt-1", "org-a"))
                .thenReturn(Optional.of(attempt(1, 80)));
        Assignment foreign = new Assignment("assignment", "org-a", "assessment", "assessment:2",
                "other-campaign", null, "candidate", 2, Instant.now(), Instant.now().plusSeconds(100), Instant.now());
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "candidate"))
                .thenReturn(Optional.of(foreign));

        ApiException error = assertThrows(ApiException.class, () -> service().review("recruiter", "org-a",
                "campaign", "candidate", new ReviewRequest("attempt-1", ReviewDecisionType.SHORTLISTED, "note")));

        assertEquals("REVIEW_NOT_READY", error.code());
        verify(decisions, never()).insert(any(ReviewDecision.class));
    }

    @Test
    void recruiterCannotDecideBeforeShortAnswerGradingFinishes() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a"))
                .thenReturn(Optional.of(campaign(true)));
        Attempt pending = new Attempt("attempt-1", "org-a", "assignment", "assessment:2", "candidate", 1,
                Instant.now().minusSeconds(90), Instant.now().minusSeconds(30), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), null, null, null, 1L);
        when(attempts.findByIdAndOrganizationId("attempt-1", "org-a"))
                .thenReturn(Optional.of(pending));
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "candidate"))
                .thenReturn(Optional.of(assignment()));

        assertEquals("REVIEW_NOT_READY", assertThrows(ApiException.class,
                () -> service().review("recruiter", "org-a", "campaign", "candidate",
                        new ReviewRequest("attempt-1", ReviewDecisionType.SHORTLISTED, "note"))).code());
        verify(decisions, never()).insert(any(ReviewDecision.class));
    }

    @Test
    void reviewerCanShortlistSubmittedCandidate() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(true)));
        when(attempts.findByIdAndOrganizationId("attempt-2", "org-a"))
                .thenReturn(Optional.of(attempt(2, 80)));
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "candidate"))
                .thenReturn(Optional.of(assignment()));

        var result = service().review("recruiter", "org-a", "campaign", "candidate",
                new ReviewRequest("attempt-2", ReviewDecisionType.SHORTLISTED, "  Strong Java  "));

        assertEquals(ReviewDecisionType.SHORTLISTED, result.decision());
        assertEquals("Strong Java", result.reviewerNote());
        verify(decisions).insert(any(ReviewDecision.class));
        verify(auditEvents).insert(org.mockito.ArgumentMatchers.<AuditEvent>argThat(event ->
                event.action() == AuditAction.TALENT_REVIEW_SAVED
                        && event.organizationId().equals("org-a")
                        && event.actorUserId().equals("recruiter")
                        && event.details().get("decision").equals("SHORTLISTED")
                        && event.details().get("note").equals("Strong Java")));
    }

    @Test
    void repeatedReviewRepairsMissingAuditWithoutChangingDecision() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a")).thenReturn(Optional.of(campaign(true)));
        when(attempts.findByIdAndOrganizationId("attempt-2", "org-a"))
                .thenReturn(Optional.of(attempt(2, 80)));
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "candidate"))
                .thenReturn(Optional.of(assignment()));
        ReviewDecision[] persisted = new ReviewDecision[1];
        when(decisions.insert(any(ReviewDecision.class))).thenAnswer(call -> {
            persisted[0] = call.getArgument(0);
            return persisted[0];
        });
        when(decisions.findByIdAndOrganizationId("campaign:candidate", "org-a"))
                .thenAnswer(call -> Optional.ofNullable(persisted[0]));
        doThrow(new IllegalStateException("audit unavailable"))
                .doAnswer(call -> call.getArgument(0)).when(auditEvents).insert(any(AuditEvent.class));
        ReviewRequest request = new ReviewRequest("attempt-2", ReviewDecisionType.SHORTLISTED, "Strong Java");

        assertThrows(IllegalStateException.class,
                () -> service().review("recruiter", "org-a", "campaign", "candidate", request));
        var repaired = service().review("recruiter", "org-a", "campaign", "candidate", request);

        assertEquals(ReviewDecisionType.SHORTLISTED, repaired.decision());
        verify(decisions).insert(any(ReviewDecision.class));
        verify(decisions, never()).save(any(ReviewDecision.class));
        org.mockito.Mockito.verify(auditEvents, org.mockito.Mockito.times(2))
                .insert(org.mockito.ArgumentMatchers.<AuditEvent>argThat(event ->
                        event.id().equals("talent-review:" + persisted[0].auditEventId())));
    }

    @Test
    void changingReviewKeepsPreviousDecisionInHistory() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a"))
                .thenReturn(Optional.of(campaign(true)));
        when(attempts.findByIdAndOrganizationId("attempt-2", "org-a"))
                .thenReturn(Optional.of(attempt(2, 80)));
        when(assignments.findByIdAndOrganizationIdAndAssignedUserId("assignment", "org-a", "candidate"))
                .thenReturn(Optional.of(assignment()));
        ReviewDecision previous = new ReviewDecision("campaign:candidate", "org-a", "campaign",
                "candidate", "attempt-2", ReviewDecisionType.HOLD, "Discuss later",
                "first-reviewer", Instant.now().minusSeconds(60), "previous-event", 1L);
        when(decisions.findByIdAndOrganizationId("campaign:candidate", "org-a"))
                .thenReturn(Optional.of(previous));

        var result = service().review("recruiter", "org-a", "campaign", "candidate",
                new ReviewRequest("attempt-2", ReviewDecisionType.SHORTLISTED, "Strong Java"));

        assertEquals(ReviewDecisionType.SHORTLISTED, result.decision());
        ArgumentCaptor<AuditEvent> history = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEvents, org.mockito.Mockito.times(2)).insert(history.capture());
        assertEquals("HOLD", history.getAllValues().get(0).details().get("decision"));
        assertEquals("SHORTLISTED", history.getAllValues().get(1).details().get("decision"));
        assertEquals("first-reviewer", history.getAllValues().get(0).actorUserId());
        assertEquals("recruiter", history.getAllValues().get(1).actorUserId());
    }

    @Test
    void reviewAuditRequiresCampaignCandidateInSameTenant() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a"))
                .thenReturn(Optional.of(campaign(true)));

        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().audit("recruiter", "org-a", "campaign", "foreign", 0)).code());
        verify(auditEvents, never()).findByOrganizationIdAndTargetTypeAndTargetId(
                any(), any(), any(), any());
    }

    @Test
    void reviewAuditReturnsTenantScopedDecisionHistory() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(talent()));
        when(campaigns.findByIdAndOrganizationId("campaign", "org-a"))
                .thenReturn(Optional.of(campaign(true)));
        when(assignments.existsByOrganizationIdAndCampaignIdAndAssignedUserId(
                "org-a", "campaign", "candidate")).thenReturn(true);
        AuditEvent event = new AuditEvent("event", "org-a", "recruiter", AuditAction.TALENT_REVIEW_SAVED,
                "TALENT_REVIEW", "campaign:candidate", Instant.now(),
                Map.of("attemptId", "attempt-2", "decision", "SHORTLISTED", "note", "Strong Java"));
        PageRequest page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
        when(auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                "org-a", "TALENT_REVIEW", "campaign:candidate", page))
                .thenReturn(new PageImpl<>(List.of(event), page, 1));

        var history = service().audit("recruiter", "org-a", "campaign", "candidate", 0);

        assertEquals(1, history.getTotalElements());
        assertEquals("Strong Java", history.getContent().getFirst().note());
        verify(access).requireRole("recruiter", "org-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.RECRUITER);
    }
}
