package com.jachai.assessment.service;

import com.jachai.assessment.dto.CreateAssessmentRequest;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.organization.service.OrganizationSettingsService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.repository.QuestionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AssessmentService {
    private final AssessmentRepository assessments;
    private final AssessmentVersionRepository versions;
    private final QuestionRepository questions;
    private final TenantAccessService access;
    private final EntitlementService entitlements;
    private final OrganizationSettingsService organizationSettings;

    public Assessment create(String userId, String organizationId, CreateAssessmentRequest request) {
        requireAuthor(userId, organizationId);
        validateQuestionIds(organizationId, request.questionIds());
        return assessments.save(new Assessment(UUID.randomUUID().toString(), organizationId, userId,
                request.title().trim(), request.durationMinutes(), request.passingPercentage(),
                List.copyOf(request.questionIds()),
                request.resultReleasePolicy() == null
                        ? organizationSettings.defaultPolicy(organizationId) : request.resultReleasePolicy(),
                AssessmentStatus.DRAFT, 0, Instant.now(), null,
                request.instructions() == null ? "" : request.instructions().trim()));
    }

    public Page<Assessment> list(String userId, String organizationId,
                                 AssessmentStatus status, Pageable pageable) {
        requireAuthor(userId, organizationId);
        return status == null ? assessments.findByOrganizationId(organizationId, pageable)
                : assessments.findByOrganizationIdAndStatus(organizationId, status, pageable);
    }

    public Assessment updateDraft(String userId, String organizationId, String assessmentId,
                                  CreateAssessmentRequest request) {
        requireAuthor(userId, organizationId);
        Assessment current = findAssessment(organizationId, assessmentId);
        if (current.status() != AssessmentStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_DRAFT", "Published assessments require a new version");
        }
        validateQuestionIds(organizationId, request.questionIds());
        Assessment updated = current.updatedDraft(request.title().trim(), request.durationMinutes(),
                request.passingPercentage(), request.questionIds(),
                request.resultReleasePolicy() == null ? current.resultReleasePolicy() : request.resultReleasePolicy(),
                request.instructions() == null ? "" : request.instructions().trim());
        try {
            return assessments.save(updated);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "DRAFT_CONFLICT", "Assessment was changed concurrently");
        }
    }

    public Page<AssessmentVersion> listVersions(String userId, String organizationId,
                                                String assessmentId, Pageable pageable) {
        requireAuthor(userId, organizationId);
        findAssessment(organizationId, assessmentId);
        return versions.findByOrganizationIdAndAssessmentId(organizationId, assessmentId, pageable);
    }

    public AssessmentVersion publish(String userId, String organizationId, String assessmentId) {
        requireAuthor(userId, organizationId);
        Assessment assessment = findAssessment(organizationId, assessmentId);
        if (assessment.status() != AssessmentStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PUBLISHED", "Assessment is already published");
        }
        AssessmentVersion version = snapshot(organizationId, assessment, assessment.latestVersion() + 1);
        String reservation = entitlements.reserve(organizationId, UsageMetric.PUBLISHED_ASSESSMENT);
        try {
            insertAndAdvance(version, assessment.published(version.version()));
            return version;
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
    }

    public AssessmentVersion reviseAndPublish(String userId, String organizationId, String assessmentId,
                                              CreateAssessmentRequest request) {
        requireAuthor(userId, organizationId);
        Assessment current = findAssessment(organizationId, assessmentId);
        if (current.status() != AssessmentStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_PUBLISHED", "Publish the draft before creating another version");
        }
        validateQuestionIds(organizationId, request.questionIds());
        var policy = request.resultReleasePolicy() == null
                ? current.resultReleasePolicy() : request.resultReleasePolicy();
        String title = request.title().trim();
        String instructions = request.instructions() == null ? "" : request.instructions().trim();
        if (title.equals(current.title()) && request.durationMinutes() == current.durationMinutes()
                && request.passingPercentage() == current.passingPercentage()
                && request.questionIds().equals(current.questionIds()) && policy == current.resultReleasePolicy()
                && instructions.equals(current.instructions() == null ? "" : current.instructions())) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CHANGES", "Change the assessment before publishing a new version");
        }
        int nextVersion = current.latestVersion() + 1;
        Assessment revised = current.revisedPublished(title, request.durationMinutes(),
                request.passingPercentage(), request.questionIds(), policy, instructions, nextVersion);
        AssessmentVersion version = snapshot(organizationId, revised, nextVersion);
        // A new version does not consume a second lifetime assessment slot.
        insertAndAdvance(version, revised);
        return version;
    }

    private Assessment findAssessment(String organizationId, String assessmentId) {
        return assessments.findByIdAndOrganizationId(assessmentId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment not found"));
    }

    private void validateQuestionIds(String organizationId, List<String> questionIds) {
        if (new HashSet<>(questionIds).size() != questionIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_QUESTION", "An assessment cannot repeat a question");
        }
        for (String questionId : questionIds) {
            if (questions.findByIdAndOrganizationId(questionId, organizationId).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", "Question does not belong to this organization");
            }
        }
    }

    private AssessmentVersion snapshot(String organizationId, Assessment assessment, int nextVersion) {
        List<AssessmentVersion.QuestionSnapshot> snapshots = new ArrayList<>();
        for (String questionId : assessment.questionIds()) {
            Question question = questions.findByIdAndOrganizationId(questionId, organizationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUESTION_MISSING", "Question is unavailable"));
            if (question.status() != QuestionStatus.APPROVED) {
                throw new ApiException(HttpStatus.CONFLICT, "QUESTION_NOT_APPROVED", "Approve every question before publishing");
            }
            snapshots.add(new AssessmentVersion.QuestionSnapshot(question.id(), question.topic(),
                    question.prompt(), question.type(), List.copyOf(question.options()),
                    java.util.Set.copyOf(question.correctOptionIds()), question.difficulty()));
        }
        return new AssessmentVersion(assessment.id() + ":" + nextVersion,
                organizationId, assessment.id(), nextVersion, assessment.title(),
                assessment.durationMinutes(), assessment.passingPercentage(), assessment.resultReleasePolicy(),
                List.copyOf(snapshots), Instant.now(), assessment.instructions());
    }

    private void insertAndAdvance(AssessmentVersion version, Assessment updated) {
        try {
            versions.insert(version);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISH_CONFLICT", "Assessment was published concurrently");
        }
        try {
            assessments.save(updated);
        } catch (RuntimeException exception) {
            versions.deleteById(version.id());
            if (exception instanceof OptimisticLockingFailureException) {
                throw new ApiException(HttpStatus.CONFLICT, "PUBLISH_CONFLICT", "Assessment was changed concurrently");
            }
            throw exception;
        }
    }

    private void requireAuthor(String userId, String organizationId) {
        access.requireRole(userId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
    }
}
