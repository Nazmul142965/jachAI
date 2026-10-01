package com.jachai.attempt.service;

import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.ResultView;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.QuestionType;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AttemptService {
    private final AssignmentRepository assignments;
    private final AttemptRepository attempts;
    private final PublishedVersionStore versions;
    private final TenantAccessService access;

    public AttemptService(AssignmentRepository assignments, AttemptRepository attempts,
                          PublishedVersionStore versions, TenantAccessService access) {
        this.assignments = assignments;
        this.attempts = attempts;
        this.versions = versions;
        this.access = access;
    }

    public List<Assignment> myAssignments(String userId, String organizationId) {
        requireParticipant(userId, organizationId);
        return assignments.findByOrganizationIdAndAssignedUserId(organizationId, userId);
    }

    public AttemptView start(String userId, String organizationId, String assignmentId) {
        requireParticipant(userId, organizationId);
        Assignment assignment = ownAssignment(userId, organizationId, assignmentId);
        Instant now = Instant.now();
        if (now.isBefore(assignment.availableFrom()) || !now.isBefore(assignment.availableUntil())) {
            throw new ApiException(HttpStatus.CONFLICT, "OUTSIDE_WINDOW", "Assessment is outside its availability window");
        }
        AssessmentVersion version = version(organizationId, assignment.assessmentVersionId());
        List<Attempt> previous = attempts.findByAssignmentIdAndOrganizationIdAndUserId(
                assignmentId, organizationId, userId);
        Attempt inProgress = previous.stream().filter(a -> a.status() == AttemptStatus.IN_PROGRESS)
                .max(Comparator.comparingInt(Attempt::attemptNumber)).orElse(null);
        if (inProgress != null && now.isBefore(inProgress.deadline())) {
            return view(inProgress, version);
        }
        if (inProgress != null) {
            finalizeAttempt(inProgress, version, now);
        }
        if (previous.size() >= assignment.attemptsAllowed()) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_LIMIT", "No attempts remain");
        }
        Instant deadline = now.plus(version.durationMinutes(), ChronoUnit.MINUTES);
        if (deadline.isAfter(assignment.availableUntil())) {
            deadline = assignment.availableUntil();
        }
        int attemptNumber = previous.stream().mapToInt(Attempt::attemptNumber).max().orElse(0) + 1;
        Attempt attempt = new Attempt(UUID.randomUUID().toString(), organizationId, assignmentId,
                version.id(), userId, attemptNumber, now, deadline, AttemptStatus.IN_PROGRESS,
                Map.of(), null, null, null, null, null);
        try {
            attempts.insert(attempt);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "START_CONFLICT", "Attempt was started concurrently; reload it");
        }
        return view(attempt, version);
    }

    public void saveAnswer(String userId, String organizationId, String attemptId,
                           String questionId, Set<String> selectedOptionIds) {
        requireParticipant(userId, organizationId);
        Attempt attempt = ownAttempt(userId, organizationId, attemptId);
        if (attempt.status() != AttemptStatus.IN_PROGRESS || !Instant.now().isBefore(attempt.deadline())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_CLOSED", "Attempt is closed");
        }
        AssessmentVersion version = version(organizationId, attempt.assessmentVersionId());
        AssessmentVersion.QuestionSnapshot question = version.questions().stream()
                .filter(item -> item.questionId().equals(questionId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Question not found"));
        Set<String> validOptions = question.options().stream().map(option -> option.id())
                .collect(java.util.stream.Collectors.toSet());
        if (!validOptions.containsAll(selectedOptionIds)
                || (question.type() == QuestionType.SINGLE_CHOICE && selectedOptionIds.size() > 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANSWER", "Selected option is invalid");
        }
        try {
            attempts.save(attempt.withAnswer(questionId, selectedOptionIds));
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ANSWER_CONFLICT", "Answer changed concurrently; reload and retry");
        }
    }

    public ResultView submit(String userId, String organizationId, String attemptId) {
        requireParticipant(userId, organizationId);
        Attempt attempt = ownAttempt(userId, organizationId, attemptId);
        if (attempt.status() == AttemptStatus.SUBMITTED) {
            return result(attempt, version(organizationId, attempt.assessmentVersionId()));
        }
        AssessmentVersion version = version(organizationId, attempt.assessmentVersionId());
        return result(finalizeAttempt(attempt, version, Instant.now()), version);
    }

    public ResultView result(String userId, String organizationId, String attemptId) {
        requireParticipant(userId, organizationId);
        Attempt attempt = ownAttempt(userId, organizationId, attemptId);
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_PENDING", "Attempt is not submitted");
        }
        return result(attempt, version(organizationId, attempt.assessmentVersionId()));
    }

    public ResultView releaseResult(String actorId, String organizationId, String attemptId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_PENDING", "Attempt is not submitted");
        }
        AssessmentVersion version = version(organizationId, attempt.assessmentVersionId());
        if (attempt.resultReleasedAt() != null) {
            return result(attempt, version);
        }
        try {
            return result(attempts.save(attempt.releaseResult(Instant.now())), version);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_CONFLICT", "Result changed concurrently; reload it");
        }
    }

    private Attempt finalizeAttempt(Attempt attempt, AssessmentVersion version, Instant now) {
        long correct = version.questions().stream()
                .filter(question -> question.correctOptionIds().equals(
                        attempt.answers().getOrDefault(question.questionId(), Set.of())))
                .count();
        int percentage = (int) Math.round(correct * 100.0 / version.questions().size());
        Attempt submitted = attempt.submitted(now, percentage, percentage >= version.passingPercentage(),
                version.resultReleasePolicy() != ResultReleasePolicy.MANUAL);
        try {
            return attempts.save(submitted);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMIT_CONFLICT", "Attempt changed concurrently; reload result");
        }
    }

    private Assignment ownAssignment(String userId, String organizationId, String assignmentId) {
        return assignments.findByIdAndOrganizationIdAndAssignedUserId(assignmentId, organizationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assignment not found"));
    }

    private Attempt ownAttempt(String userId, String organizationId, String attemptId) {
        return attempts.findByIdAndOrganizationIdAndUserId(attemptId, organizationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
    }

    private AssessmentVersion version(String organizationId, String versionId) {
        return versions.get(organizationId, versionId);
    }

    private AttemptView view(Attempt attempt, AssessmentVersion version) {
        return AttemptView.from(attempt, version.questions().stream()
                .map(item -> new AttemptView.QuestionView(item.questionId(), item.topic(), item.prompt(),
                        item.type(), item.options()))
                .toList());
    }

    private ResultView result(Attempt attempt, AssessmentVersion version) {
        boolean released = attempt.resultReleasedAt() != null
                || version.resultReleasePolicy() != ResultReleasePolicy.MANUAL;
        if (!released) {
            return new ResultView(attempt.id(), false, null, null, attempt.submittedAt(), null, List.of());
        }
        Map<String, int[]> topicCounts = new LinkedHashMap<>();
        for (AssessmentVersion.QuestionSnapshot question : version.questions()) {
            int[] counts = topicCounts.computeIfAbsent(question.topic(), ignored -> new int[2]);
            counts[1]++;
            if (question.correctOptionIds().equals(attempt.answers().getOrDefault(question.questionId(), Set.of()))) {
                counts[0]++;
            }
        }
        List<ResultView.TopicScore> topics = topicCounts.entrySet().stream()
                .map(entry -> new ResultView.TopicScore(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
        return new ResultView(attempt.id(), true, attempt.percentage(), attempt.passed(),
                attempt.submittedAt(), attempt.resultReleasedAt(), topics);
    }

    private void requireParticipant(String userId, String organizationId) {
        access.requireRole(userId, organizationId, OrganizationRole.LEARNER, OrganizationRole.CANDIDATE);
    }
}
