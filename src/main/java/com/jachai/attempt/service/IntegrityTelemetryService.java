package com.jachai.attempt.service;

import com.jachai.attempt.dto.IntegrityReport;
import com.jachai.attempt.dto.RecordTelemetryRequest;
import com.jachai.attempt.dto.TelemetryEventView;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.model.TelemetryEvent;
import com.jachai.attempt.model.TelemetryEventType;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.attempt.repository.TelemetryEventRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IntegrityTelemetryService {
    private static final Duration RETENTION = Duration.ofDays(90);
    private static final long MAX_STORED_EVENTS_PER_ATTEMPT = 200;
    private static final long TAB_SWITCH_WARNING_LIMIT = 3;
    private static final Duration RAPID_ANSWER_WINDOW = Duration.ofSeconds(2);

    private final TelemetryEventRepository events;
    private final AttemptRepository attempts;
    private final AssignmentRepository assignments;
    private final OrganizationRepository organizations;
    private final TenantAccessService access;

    public TelemetryEventView record(String candidateId, String organizationId, String attemptId,
                                     RecordTelemetryRequest request) {
        access.requireRole(candidateId, organizationId, OrganizationRole.CANDIDATE);
        requireTalent(organizationId);
        Attempt attempt = attempts.findByIdAndOrganizationIdAndUserId(attemptId, organizationId, candidateId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        requireCampaignAssignment(attempt, organizationId);
        Instant now = Instant.now();
        if (attempt.status() != AttemptStatus.IN_PROGRESS || !now.isBefore(attempt.deadline())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_CLOSED", "Activity can only be recorded while a test is in progress.");
        }
        if (request.type() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT", "Event type is required");
        }
        String questionId = null;
        if (request.type() == TelemetryEventType.ANSWER_SAVED) {
            questionId = request.questionId() == null ? "" : request.questionId().trim();
            if (questionId.isEmpty() || questionId.length() > 100) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT", "Question ID is required for an answer event");
            }
        }
        if (events.countByOrganizationIdAndAttemptIdAndExpiresAtAfter(organizationId, attemptId, now)
                >= MAX_STORED_EVENTS_PER_ATTEMPT) {
            throw new ApiException(HttpStatus.CONFLICT, "TELEMETRY_LIMIT",
                    "This attempt has recorded as much activity as allowed.");
        }
        TelemetryEvent event = new TelemetryEvent(UUID.randomUUID().toString(), organizationId,
                attemptId, candidateId, request.type(), questionId, now, now.plus(RETENTION));
        TelemetryEvent saved = events.insert(event);
        long tabSwitches = events.countByOrganizationIdAndAttemptIdAndTypeAndExpiresAtAfter(
                organizationId, attemptId, TelemetryEventType.TAB_HIDDEN, now);
        boolean warning = request.type() == TelemetryEventType.TAB_HIDDEN
                && tabSwitches >= TAB_SWITCH_WARNING_LIMIT;
        return new TelemetryEventView(saved.type(), saved.questionId(), saved.receivedAt(),
                tabSwitches, warning,
                warning ? "You have left the exam tab three times. This integrity event was reported to the examiner."
                        : null);
    }

    public IntegrityReport report(String actorId, String organizationId, String attemptId) {
        submittedCampaignAttempt(actorId, organizationId, attemptId);
        List<TelemetryEvent> all = events
                .findByOrganizationIdAndAttemptIdAndExpiresAtAfterOrderByReceivedAtAsc(
                        organizationId, attemptId, Instant.now());
        Map<TelemetryEventType, Long> counts = new EnumMap<>(TelemetryEventType.class);
        Instant previousAnswerAt = null;
        String previousQuestionId = null;
        int rapid = 0;
        for (TelemetryEvent event : all) {
            counts.merge(event.type(), 1L, Long::sum);
            if (event.type() == TelemetryEventType.ANSWER_SAVED) {
                if (previousAnswerAt != null && !Objects.equals(event.questionId(), previousQuestionId)
                        && Duration.between(previousAnswerAt, event.receivedAt())
                                .compareTo(RAPID_ANSWER_WINDOW) < 0) {
                    rapid++;
                }
                previousAnswerAt = event.receivedAt();
                previousQuestionId = event.questionId();
            }
        }
        long tabSwitches = counts.getOrDefault(TelemetryEventType.TAB_HIDDEN, 0L);
        return new IntegrityReport(attemptId, all.size(), Map.copyOf(counts), rapid,
                tabSwitches, tabSwitches >= TAB_SWITCH_WARNING_LIMIT,
                "Client-reported signals are evidence for human review, not proof of cheating");
    }

    public Page<TelemetryEventView> events(String actorId, String organizationId,
                                           String attemptId, Pageable pageable) {
        submittedCampaignAttempt(actorId, organizationId, attemptId);
        return events.findByOrganizationIdAndAttemptIdAndExpiresAtAfter(
                organizationId, attemptId, Instant.now(), pageable).map(TelemetryEventView::from);
    }

    private Attempt submittedCampaignAttempt(String actorId, String organizationId, String attemptId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        requireTalent(organizationId);
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        requireCampaignAssignment(attempt, organizationId);
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_NOT_READY", "Submit the attempt before integrity review");
        }
        return attempt;
    }

    private void requireCampaignAssignment(Attempt attempt, String organizationId) {
        Assignment assignment = assignments.findByIdAndOrganizationId(attempt.assignmentId(), organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign attempt not found"));
        if (assignment.campaignId() == null || !attempt.userId().equals(assignment.assignedUserId())
                || !attempt.assessmentVersionId().equals(assignment.assessmentVersionId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign attempt not found");
        }
    }

    private void requireTalent(String organizationId) {
        var organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.TALENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TALENT_ONLY", "Integrity review is available in Talent organizations");
        }
    }
}
