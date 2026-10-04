package com.jachai.academy.service;

import com.jachai.academy.dto.FeedbackView;
import com.jachai.academy.dto.WriteFeedbackRequest;
import com.jachai.academy.model.FeedbackStatus;
import com.jachai.academy.model.InstructorFeedback;
import com.jachai.academy.repository.InstructorFeedbackRepository;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class InstructorFeedbackService {
    private final InstructorFeedbackRepository feedback;
    private final AttemptRepository attempts;
    private final OrganizationRepository organizations;
    private final PublishedVersionStore versions;
    private final TenantAccessService access;
    private final UserRepository users;
    private final EmailSender emailSender;
    private final String publicBaseUrl;

    public InstructorFeedbackService(InstructorFeedbackRepository feedback, AttemptRepository attempts,
                                     OrganizationRepository organizations, PublishedVersionStore versions,
                                     TenantAccessService access, UserRepository users, EmailSender emailSender,
                                     @Value("${app.public-base-url}") String publicBaseUrl) {
        this.feedback = feedback;
        this.attempts = attempts;
        this.organizations = organizations;
        this.versions = versions;
        this.access = access;
        this.users = users;
        this.emailSender = emailSender;
        this.publicBaseUrl = publicBaseUrl;
    }

    public FeedbackView writeDraft(String actorId, String organizationId, String attemptId,
                                   WriteFeedbackRequest request) {
        requireAcademyStaff(actorId, organizationId);
        Attempt attempt = submittedAttempt(organizationId, attemptId);
        Instant now = Instant.now();
        InstructorFeedback existing = feedback.findByOrganizationIdAndAttemptId(organizationId, attemptId)
                .orElse(null);
        if (existing != null && existing.status() == FeedbackStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "FEEDBACK_PUBLISHED",
                    "Published feedback cannot be changed");
        }
        String summary = request.summary().trim();
        String nextSteps = request.nextSteps().trim();
        InstructorFeedback next = existing == null
                ? new InstructorFeedback(UUID.randomUUID().toString(), organizationId, attemptId,
                        attempt.userId(), actorId, summary, nextSteps, FeedbackStatus.DRAFT,
                        now, now, null, null)
                : existing.revise(actorId, summary, nextSteps, now);
        try {
            return FeedbackView.from(existing == null ? feedback.insert(next) : feedback.save(next));
        } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "FEEDBACK_CONFLICT", "Feedback changed concurrently; reload it");
        }
    }

    public FeedbackView publish(String actorId, String organizationId, String attemptId) {
        requireAcademyStaff(actorId, organizationId);
        Attempt attempt = submittedAttempt(organizationId, attemptId);
        InstructorFeedback current = feedback.findByOrganizationIdAndAttemptId(organizationId, attemptId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Feedback not found"));
        if (current.status() == FeedbackStatus.PUBLISHED) {
            queuePublishedEmail(current);
            return FeedbackView.from(current);
        }
        boolean released = attempt.percentage() != null && (attempt.resultReleasedAt() != null
                || versions.get(organizationId, attempt.assessmentVersionId()).resultReleasePolicy()
                        != ResultReleasePolicy.MANUAL);
        if (!released) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_NOT_RELEASED",
                    "Release the result before publishing feedback");
        }
        try {
            InstructorFeedback published = feedback.save(current.publish(Instant.now()));
            queuePublishedEmail(published);
            return FeedbackView.from(published);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "FEEDBACK_CONFLICT", "Feedback changed concurrently; reload it");
        }
    }

    private void queuePublishedEmail(InstructorFeedback published) {
        try {
            users.findById(published.learnerUserId()).ifPresent(user -> {
                if (user.emailVerified() && !user.disabled()) {
                    emailSender.sendFeedbackPublished(published.id(), user.email(), user.displayName(),
                            publicBaseUrl.replaceAll("/+$", "") + "/");
                }
            });
        } catch (RuntimeException exception) {
            // Published feedback remains authoritative. A publish retry repairs enqueue failure.
            log.warn("Feedback email enqueue failed for feedback {}: {}",
                    published.id(), exception.getClass().getSimpleName());
        }
    }

    public FeedbackView staffView(String actorId, String organizationId, String attemptId) {
        requireAcademyStaff(actorId, organizationId);
        return FeedbackView.from(feedback.findByOrganizationIdAndAttemptId(organizationId, attemptId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Feedback not found")));
    }

    public FeedbackView learnerView(String learnerId, String organizationId, String attemptId) {
        requireAcademyMember(learnerId, organizationId);
        Attempt attempt = attempts.findByIdAndOrganizationIdAndUserId(attemptId, organizationId, learnerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Feedback not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Feedback not found");
        }
        InstructorFeedback found = feedback.findByOrganizationIdAndAttemptIdAndLearnerUserId(
                        organizationId, attemptId, learnerId)
                .filter(item -> item.status() == FeedbackStatus.PUBLISHED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Feedback not found"));
        return FeedbackView.from(found);
    }

    private Attempt submittedAttempt(String organizationId, String attemptId) {
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_PENDING", "Attempt is not submitted");
        }
        return attempt;
    }

    private void requireAcademyStaff(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.INSTRUCTOR);
        requireAcademy(organizationId);
    }

    private void requireAcademyMember(String learnerId, String organizationId) {
        access.requireRole(learnerId, organizationId, OrganizationRole.LEARNER);
        requireAcademy(organizationId);
    }

    private void requireAcademy(String organizationId) {
        var organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.ACADEMY) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMY_ONLY", "Feedback is available in Academy organizations");
        }
    }
}
