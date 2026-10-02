package com.jachai.talent.service;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
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
import com.jachai.talent.dto.CampaignInviteView;
import com.jachai.talent.dto.CampaignView;
import com.jachai.talent.dto.CandidateComparison;
import com.jachai.talent.dto.CreateCampaignRequest;
import com.jachai.talent.dto.ReviewRequest;
import com.jachai.talent.dto.ReviewAuditView;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.model.ReviewDecision;
import com.jachai.talent.repository.HiringCampaignRepository;
import com.jachai.talent.repository.ReviewDecisionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TalentCampaignService {
    private final HiringCampaignRepository campaigns;
    private final ReviewDecisionRepository decisions;
    private final AssessmentRepository assessments;
    private final AssignmentRepository assignments;
    private final AttemptRepository attempts;
    private final OrganizationRepository organizations;
    private final TenantAccessService access;
    private final EntitlementService entitlements;
    private final AuditEventRepository auditEvents;

    public CampaignView create(String actorId, String organizationId, CreateCampaignRequest request) {
        requireTalentStaff(actorId, organizationId);
        Assessment assessment = assessments.findByIdAndOrganizationId(request.assessmentId(), organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment not found"));
        if (assessment.status() != AssessmentStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_PUBLISHED", "Publish the assessment first");
        }
        HiringCampaign campaign = new HiringCampaign(UUID.randomUUID().toString(), organizationId,
                assessment.id(), assessment.id() + ":" + assessment.latestVersion(),
                request.title().trim(), actorId, true, Instant.now(), null);
        return CampaignView.from(campaigns.insert(campaign));
    }

    public Page<CampaignView> list(String actorId, String organizationId, Pageable pageable) {
        requireTalentStaff(actorId, organizationId);
        return campaigns.findByOrganizationId(organizationId, pageable).map(CampaignView::from);
    }

    public CampaignView close(String actorId, String organizationId, String campaignId) {
        requireTalentStaff(actorId, organizationId);
        HiringCampaign campaign = ownCampaign(organizationId, campaignId);
        if (!campaign.open()) return CampaignView.from(campaign);
        try {
            return CampaignView.from(campaigns.save(campaign.close()));
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_CONFLICT", "Campaign changed concurrently; reload it");
        }
    }

    public CampaignInviteView invite(String actorId, String organizationId, String campaignId,
                                     CreateAssignmentRequest request) {
        requireTalentStaff(actorId, organizationId);
        HiringCampaign campaign = ownCampaign(organizationId, campaignId);
        if (!campaign.open()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_CLOSED", "Campaign is closed to new invitations");
        }
        access.requireRole(request.userId(), organizationId, OrganizationRole.CANDIDATE);
        if (!request.availableUntil().isAfter(request.availableFrom())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW", "Availability end must follow start");
        }
        String assignmentId = UUID.nameUUIDFromBytes(
                (campaignId + ":" + request.userId()).getBytes(StandardCharsets.UTF_8)).toString();
        Assignment assignment = new Assignment(assignmentId, organizationId, campaign.assessmentId(),
                campaign.assessmentVersionId(), campaignId, null, request.userId(), request.attemptsAllowed(),
                request.availableFrom(), request.availableUntil(), Instant.now());
        String reservation = entitlements.reserve(organizationId, UsageMetric.INVITATION);
        try {
            return CampaignInviteView.from(assignments.insert(assignment));
        } catch (DuplicateKeyException exception) {
            entitlements.refund(reservation);
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_INVITED", "Candidate is already invited to this campaign");
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
    }

    public Page<CandidateComparison> compare(String actorId, String organizationId,
                                              String campaignId, Pageable pageable) {
        requireTalentStaff(actorId, organizationId);
        ownCampaign(organizationId, campaignId);
        Page<Assignment> page = assignments.findByOrganizationIdAndCampaignId(organizationId, campaignId, pageable);
        if (page.isEmpty()) return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        List<String> assignmentIds = page.getContent().stream().map(Assignment::id).toList();
        List<String> candidateIds = page.getContent().stream().map(Assignment::assignedUserId).toList();
        Map<String, Attempt> latest = attempts.findByOrganizationIdAndAssignmentIdInAndStatus(
                        organizationId, assignmentIds, AttemptStatus.SUBMITTED).stream()
                .collect(Collectors.toMap(Attempt::assignmentId, Function.identity(),
                        (a, b) -> Comparator.comparingInt(Attempt::attemptNumber).compare(a, b) >= 0 ? a : b));
        Map<String, ReviewDecision> reviews = decisions.findByOrganizationIdAndCampaignIdAndCandidateUserIdIn(
                        organizationId, campaignId, candidateIds).stream()
                .collect(Collectors.toMap(ReviewDecision::candidateUserId, Function.identity()));
        List<CandidateComparison> rows = page.getContent().stream().map(assignment -> {
            Attempt attempt = latest.get(assignment.id());
            ReviewDecision review = reviews.get(assignment.assignedUserId());
            if (attempt == null || (review != null && !attempt.id().equals(review.attemptId()))) {
                review = null;
            }
            return new CandidateComparison(assignment.assignedUserId(), assignment.id(),
                    attempt == null ? null : attempt.id(), attempt == null ? null : attempt.percentage(),
                    attempt == null ? null : attempt.passed(), attempt == null ? null : attempt.submittedAt(),
                    review == null ? null : review.decision(), review == null ? null : review.note());
        }).toList();
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    public CandidateComparison review(String actorId, String organizationId, String campaignId,
                                       String candidateId, ReviewRequest request) {
        requireTalentStaff(actorId, organizationId);
        ownCampaign(organizationId, campaignId);
        Attempt attempt = attempts.findByIdAndOrganizationId(request.attemptId(), organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        Assignment assignment = assignments.findByIdAndOrganizationIdAndAssignedUserId(
                        attempt.assignmentId(), organizationId, candidateId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign candidate not found"));
        if (!campaignId.equals(assignment.campaignId()) || !candidateId.equals(attempt.userId())
                || !assignment.assessmentVersionId().equals(attempt.assessmentVersionId())
                || attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_READY", "A submitted campaign attempt is required");
        }
        String note = request.note() == null ? "" : request.note().trim();
        String id = campaignId + ":" + candidateId;
        ReviewDecision existing = decisions.findByIdAndOrganizationId(id, organizationId).orElse(null);
        if (existing != null) {
            ensureReviewAudit(existing);
            if (existing.attemptId().equals(attempt.id()) && existing.decision() == request.decision()
                    && existing.note().equals(note)) {
                return new CandidateComparison(candidateId, assignment.id(), attempt.id(), attempt.percentage(),
                        attempt.passed(), attempt.submittedAt(), existing.decision(), existing.note());
            }
        }
        ReviewDecision next = existing == null
                ? new ReviewDecision(id, organizationId, campaignId, candidateId, attempt.id(),
                        request.decision(), note, actorId, Instant.now(), UUID.randomUUID().toString(), null)
                : existing.update(attempt.id(), request.decision(), note, actorId, Instant.now());
        try {
            if (existing == null) decisions.insert(next);
            else decisions.save(next);
        } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_CONFLICT", "Review changed concurrently; reload it");
        }
        ensureReviewAudit(next);
        return new CandidateComparison(candidateId, assignment.id(), attempt.id(), attempt.percentage(),
                attempt.passed(), attempt.submittedAt(), next.decision(), next.note());
    }

    public Page<ReviewAuditView> audit(String actorId, String organizationId, String campaignId,
                                       String candidateId, int page) {
        requireTalentStaff(actorId, organizationId);
        ownCampaign(organizationId, campaignId);
        if (!assignments.existsByOrganizationIdAndCampaignIdAndAssignedUserId(
                organizationId, campaignId, candidateId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign candidate not found");
        }
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        return auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                organizationId, "TALENT_REVIEW", campaignId + ":" + candidateId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .map(ReviewAuditView::from);
    }

    private void ensureReviewAudit(ReviewDecision decision) {
        if (decision.reviewerUserId() == null || decision.updatedAt() == null) return;
        String eventId = decision.auditEventId() == null
                ? "talent-review:" + decision.id() + ":legacy:" + decision.revision()
                : "talent-review:" + decision.auditEventId();
        AuditEvent event = new AuditEvent(eventId, decision.organizationId(), decision.reviewerUserId(),
                AuditAction.TALENT_REVIEW_SAVED, "TALENT_REVIEW", decision.id(), decision.updatedAt(),
                Map.of("attemptId", decision.attemptId(), "decision", decision.decision().name(),
                        "note", decision.note()));
        try {
            auditEvents.insert(event);
        } catch (DuplicateKeyException ignored) {
            // A retry or concurrent request already recorded this immutable review state.
        }
    }

    private HiringCampaign ownCampaign(String organizationId, String campaignId) {
        return campaigns.findByIdAndOrganizationId(campaignId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign not found"));
    }

    private void requireTalentStaff(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.TALENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TALENT_ONLY", "Hiring campaigns require a Talent organization");
        }
    }
}
