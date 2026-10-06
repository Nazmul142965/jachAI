package com.jachai.attempt.service;

import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.AttemptSummary;
import com.jachai.attempt.dto.AssignmentView;
import com.jachai.attempt.dto.ResultView;
import com.jachai.attempt.dto.ResultState;
import com.jachai.attempt.dto.ReviewAttemptView;
import com.jachai.attempt.dto.StaffAttemptReviewView;
import com.jachai.attempt.dto.ManualGradeAuditView;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.attempt.repository.AttemptAnswerStore;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.model.QuestionType;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AttemptService {
    private final AssignmentRepository assignments;
    private final AttemptRepository attempts;
    private final AttemptAnswerStore answerStore;
    private final PublishedVersionStore versions;
    private final AssessmentVersionRepository versionRepository;
    private final TenantAccessService access;
    private final UserRepository users;
    private final EmailSender emailSender;
    private final AuditEventRepository auditEvents;
    private final String publicBaseUrl;

    public AttemptService(AssignmentRepository assignments, AttemptRepository attempts,
                          AttemptAnswerStore answerStore,
                          PublishedVersionStore versions, AssessmentVersionRepository versionRepository,
                          TenantAccessService access,
                          UserRepository users, EmailSender emailSender, AuditEventRepository auditEvents,
                          @Value("${app.public-base-url}") String publicBaseUrl) {
        this.assignments = assignments;
        this.attempts = attempts;
        this.answerStore = answerStore;
        this.versions = versions;
        this.versionRepository = versionRepository;
        this.access = access;
        this.users = users;
        this.emailSender = emailSender;
        this.auditEvents = auditEvents;
        this.publicBaseUrl = publicBaseUrl;
    }

    public Page<AssignmentView> myAssignments(String userId, String organizationId, Pageable pageable) {
        requireParticipant(userId, organizationId);
        Page<Assignment> page = assignments.findByOrganizationIdAndAssignedUserId(organizationId, userId, pageable);
        if (page.isEmpty()) return page.map(assignment -> AssignmentView.from(assignment, null));
        var versionIds = page.getContent().stream().map(Assignment::assessmentVersionId)
                .collect(Collectors.toSet());
        Map<String, AssessmentVersion> metadata = versionRepository
                .findByIdInAndOrganizationId(versionIds, organizationId).stream()
                .collect(Collectors.toMap(AssessmentVersion::id, Function.identity()));
        return page.map(assignment -> AssignmentView.from(assignment,
                metadata.get(assignment.assessmentVersionId())));
    }

    public AssignmentView myAssignment(String userId, String organizationId, String assignmentId) {
        requireParticipant(userId, organizationId);
        Assignment assignment = ownAssignment(userId, organizationId, assignmentId);
        return AssignmentView.from(assignment, version(organizationId, assignment.assessmentVersionId()));
    }

    public List<AttemptSummary> myAttempts(String userId, String organizationId, String assignmentId) {
        requireParticipant(userId, organizationId);
        ownAssignment(userId, organizationId, assignmentId);
        return attempts.findByAssignmentIdAndOrganizationIdAndUserId(assignmentId, organizationId, userId)
                .stream().sorted(Comparator.comparingInt(Attempt::attemptNumber))
                .map(AttemptSummary::from).toList();
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
        if (question.type() == QuestionType.SHORT_ANSWER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEXT_ANSWER_REQUIRED", "Use the text-answer endpoint");
        }
        Set<String> validOptions = question.options().stream().map(option -> option.id())
                .collect(java.util.stream.Collectors.toSet());
        if (!validOptions.containsAll(selectedOptionIds)
                || (question.type() == QuestionType.SINGLE_CHOICE && selectedOptionIds.size() > 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANSWER", "Selected option is invalid");
        }
        if (!answerStore.saveOptions(attempt, questionId, selectedOptionIds)) answerWriteConflict(userId,
                organizationId, attemptId);
    }

    public void saveTextAnswer(String userId, String organizationId, String attemptId,
                               String questionId, String text) {
        requireParticipant(userId, organizationId);
        Attempt attempt = ownAttempt(userId, organizationId, attemptId);
        if (attempt.status() != AttemptStatus.IN_PROGRESS || !Instant.now().isBefore(attempt.deadline())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_CLOSED", "Attempt is closed");
        }
        AssessmentVersion version = version(organizationId, attempt.assessmentVersionId());
        AssessmentVersion.QuestionSnapshot question = version.questions().stream()
                .filter(item -> item.questionId().equals(questionId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Question not found"));
        if (question.type() != QuestionType.SHORT_ANSWER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPTIONS_REQUIRED", "Use the option-answer endpoint");
        }
        String answer = text.trim();
        if (answer.isEmpty() || answer.length() > 4000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ANSWER", "Text answer must contain 1–4000 characters");
        }
        if (!answerStore.saveText(attempt, questionId, answer)) answerWriteConflict(userId,
                organizationId, attemptId);
    }

    private void answerWriteConflict(String userId, String organizationId, String attemptId) {
        Attempt current = ownAttempt(userId, organizationId, attemptId);
        if (current.status() != AttemptStatus.IN_PROGRESS || !Instant.now().isBefore(current.deadline())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_CLOSED", "Attempt is closed");
        }
        throw new ApiException(HttpStatus.CONFLICT, "ANSWER_CONFLICT",
                "Answer changed concurrently; reload and retry");
    }

    public ResultView submit(String userId, String organizationId, String attemptId) {
        requireParticipant(userId, organizationId);
        Attempt attempt = ownAttempt(userId, organizationId, attemptId);
        if (attempt.status() == AttemptStatus.SUBMITTED) {
            queueReleasedEmail(attempt);
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
        if (attempt.percentage() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MANUAL_GRADING_PENDING", "Grade every answered short question before release");
        }
        ensureGradeAudits(attempt);
        if (attempt.resultReleasedAt() != null) {
            queueReleasedEmail(attempt);
            return result(attempt, version);
        }
        try {
            Attempt released = attempts.save(attempt.releaseResult(Instant.now()));
            queueReleasedEmail(released);
            return result(released, version);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_CONFLICT", "Result changed concurrently; reload it");
        }
    }

    /**
     * Resolves display names for a set of participants in one call.
     *
     * <p>A name can be absent — the account may have been removed — and the map simply has no
     * entry for it. Callers keep the user id, so a row without a name still identifies someone.
     */
    private Map<String, String> displayNames(List<String> userIds) {
        if (userIds.isEmpty()) return Map.of();
        Map<String, String> names = new HashMap<>();
        users.findAllById(userIds).forEach(user -> {
            if (user.displayName() != null && !user.displayName().isBlank()) {
                names.put(user.id(), user.displayName());
            }
        });
        return names;
    }

    public Page<ReviewAttemptView> pendingReview(String actorId, String organizationId, int page) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        var queue = attempts.findByOrganizationIdAndStatusAndResultReleasedAtIsNull(
                organizationId, AttemptStatus.SUBMITTED,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "submittedAt")));
        // One lookup for the whole page rather than one per row. The ids come from attempts
        // already scoped to this organization, so no account outside the tenant is reachable.
        Map<String, String> names = displayNames(queue.getContent().stream()
                .map(Attempt::userId).toList());
        return queue.map(attempt -> new ReviewAttemptView(attempt.id(), attempt.assignmentId(),
                attempt.userId(), names.get(attempt.userId()),
                version(organizationId, attempt.assessmentVersionId()).title(),
                attempt.attemptNumber(), attempt.percentage(), attempt.passed(),
                attempt.submittedAt()));
    }

    public StaffAttemptReviewView staffReview(String actorId, String organizationId, String attemptId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_PENDING", "Attempt is not submitted");
        }
        AssessmentVersion version = version(organizationId, attempt.assessmentVersionId());
        ensureGradeAudits(attempt);
        List<StaffAttemptReviewView.QuestionReview> questions = version.questions().stream()
                .map(question -> {
                    Set<String> selected = attempt.answers().getOrDefault(question.questionId(), Set.of());
                    Attempt.ManualGrade grade = attempt.manualGrades().get(question.questionId());
                    boolean shortAnswer = question.type() == QuestionType.SHORT_ANSWER;
                    Boolean correct;
                    if (!shortAnswer) {
                        correct = question.correctOptionIds().equals(selected);
                    } else if (grade != null) {
                        correct = grade.correct();
                    } else {
                        correct = attempt.textAnswers().containsKey(question.questionId()) ? null : Boolean.FALSE;
                    }
                    return new StaffAttemptReviewView.QuestionReview(question.questionId(),
                            question.topic(), question.prompt(), question.type(), question.options(),
                            selected, question.correctOptionIds(),
                            shortAnswer ? attempt.textAnswers().get(question.questionId()) : null,
                            correct,
                            grade == null ? null : grade.actorUserId(),
                            grade == null ? null : grade.gradedAt());
                }).toList();
        return new StaffAttemptReviewView(attempt.id(), attempt.assignmentId(), attempt.userId(),
                displayNames(List.of(attempt.userId())).get(attempt.userId()),
                version.title(), attempt.attemptNumber(), attempt.submittedAt(),
                attempt.percentage(), attempt.passed(), attempt.resultReleasedAt(), questions);
    }

    public StaffAttemptReviewView gradeTextAnswer(String actorId, String organizationId,
                                                   String attemptId, String questionId, boolean correct) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_CLOSED", "Only submitted attempts can be graded");
        }
        AssessmentVersion version = version(organizationId, attempt.assessmentVersionId());
        AssessmentVersion.QuestionSnapshot question = version.questions().stream()
                .filter(item -> item.questionId().equals(questionId) && item.type() == QuestionType.SHORT_ANSWER)
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Short-answer question not found"));
        if (!attempt.textAnswers().containsKey(question.questionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ANSWER_MISSING", "No submitted text answer to grade");
        }
        Attempt.ManualGrade existing = attempt.manualGrades().get(questionId);
        if (existing != null) {
            ensureGradeAudits(attempt);
            if (existing.correct() != correct) {
                throw new ApiException(HttpStatus.CONFLICT, "GRADE_FINAL", "This answer has already been graded");
            }
            queueReleasedEmail(attempt);
            return staffReview(actorId, organizationId, attemptId);
        }
        if (attempt.resultReleasedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_CLOSED", "Released attempts cannot be graded");
        }
        Instant now = Instant.now();
        Attempt.ManualGrade grade = new Attempt.ManualGrade(correct, actorId, now, UUID.randomUUID().toString());
        Attempt staged = attempt.graded(questionId, grade, null, null, null);
        Integer percentage = score(staged, version);
        Boolean passed = percentage == null ? null : percentage >= version.passingPercentage();
        Instant releasedAt = percentage != null && version.resultReleasePolicy() != ResultReleasePolicy.MANUAL
                ? now : null;
        try {
            Attempt saved = attempts.save(attempt.graded(questionId, grade, percentage, passed, releasedAt));
            ensureGradeAudits(saved);
            queueReleasedEmail(saved);
            return staffReview(actorId, organizationId, attemptId);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_CONFLICT", "Grade changed concurrently; reload it");
        }
    }

    public Page<ManualGradeAuditView> manualGradeAudit(String actorId, String organizationId,
                                                        String attemptId, int page) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        ensureGradeAudits(attempt);
        var history = auditEvents.findByOrganizationIdAndTargetTypeAndTargetIdAndAction(
                organizationId, "ATTEMPT", attemptId, AuditAction.MANUAL_ANSWER_GRADED,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "occurredAt")));
        // The same reuse as pendingReview: one lookup for the page, ids already tenant-scoped.
        Map<String, String> graders = displayNames(history.getContent().stream()
                .map(AuditEvent::actorUserId).toList());
        return history.map(event ->
                ManualGradeAuditView.from(event, graders.get(event.actorUserId())));
    }

    private void ensureGradeAudits(Attempt attempt) {
        for (var entry : attempt.manualGrades().entrySet()) {
            Attempt.ManualGrade grade = entry.getValue();
            try {
                auditEvents.insert(new AuditEvent("manual-grade:" + grade.changeId(),
                        attempt.organizationId(), grade.actorUserId(), AuditAction.MANUAL_ANSWER_GRADED,
                        "ATTEMPT", attempt.id(), grade.gradedAt(),
                        Map.of("questionId", entry.getKey(), "correct", Boolean.toString(grade.correct()))));
            } catch (DuplicateKeyException ignored) {
                // A previous attempt already persisted this immutable grade event.
            }
        }
    }

    private Attempt finalizeAttempt(Attempt attempt, AssessmentVersion version, Instant now) {
        Integer percentage = score(attempt, version);
        Boolean passed = percentage == null ? null : percentage >= version.passingPercentage();
        Attempt submitted = attempt.submitted(now, percentage, passed,
                percentage != null && version.resultReleasePolicy() != ResultReleasePolicy.MANUAL);
        try {
            Attempt saved = attempts.save(submitted);
            queueReleasedEmail(saved);
            return saved;
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMIT_CONFLICT", "Attempt changed concurrently; reload result");
        }
    }

    private Integer score(Attempt attempt, AssessmentVersion version) {
        long correct = 0;
        for (AssessmentVersion.QuestionSnapshot question : version.questions()) {
            if (question.type() == QuestionType.SHORT_ANSWER) {
                if (attempt.textAnswers().containsKey(question.questionId())) {
                    Attempt.ManualGrade grade = attempt.manualGrades().get(question.questionId());
                    if (grade == null) return null;
                    if (grade.correct()) correct++;
                }
            } else if (question.correctOptionIds().equals(
                    attempt.answers().getOrDefault(question.questionId(), Set.of()))) {
                correct++;
            }
        }
        return (int) Math.round(correct * 100.0 / version.questions().size());
    }

    private void queueReleasedEmail(Attempt attempt) {
        if (attempt.resultReleasedAt() == null) return;
        try {
            users.findById(attempt.userId()).ifPresent(user ->
                    emailSender.sendResultReleased(attempt.id(), user.email(), user.displayName(),
                            publicBaseUrl.replaceAll("/+$", "") + "/"));
        } catch (RuntimeException exception) {
            // The released result remains authoritative; a submit/release retry repairs the notification.
            log.warn("Result email enqueue failed for attempt {}: {}",
                    attempt.id(), exception.getClass().getSimpleName());
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
        boolean released = attempt.percentage() != null && (attempt.resultReleasedAt() != null
                || version.resultReleasePolicy() != ResultReleasePolicy.MANUAL);
        if (!released) {
            ResultState state = attempt.percentage() == null
                    ? ResultState.GRADING_PENDING : ResultState.RELEASE_PENDING;
            return new ResultView(attempt.id(), false, null, null, attempt.submittedAt(), null, List.of(), state);
        }
        Map<String, int[]> topicCounts = new LinkedHashMap<>();
        for (AssessmentVersion.QuestionSnapshot question : version.questions()) {
            int[] counts = topicCounts.computeIfAbsent(question.topic(), ignored -> new int[2]);
            counts[1]++;
            if (question.type() == QuestionType.SHORT_ANSWER
                    ? attempt.manualGrades().get(question.questionId()) != null
                            && attempt.manualGrades().get(question.questionId()).correct()
                    : question.correctOptionIds().equals(attempt.answers().getOrDefault(question.questionId(), Set.of()))) {
                counts[0]++;
            }
        }
        List<ResultView.TopicScore> topics = topicCounts.entrySet().stream()
                .map(entry -> new ResultView.TopicScore(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
        return new ResultView(attempt.id(), true, attempt.percentage(), attempt.passed(),
                attempt.submittedAt(), attempt.resultReleasedAt(), topics, ResultState.RELEASED);
    }

    private void requireParticipant(String userId, String organizationId) {
        access.requireRole(userId, organizationId, OrganizationRole.LEARNER, OrganizationRole.CANDIDATE);
    }
}
