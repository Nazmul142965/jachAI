package com.jachai.assessment.service;

import com.jachai.assessment.dto.CreateAssessmentRequest;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
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
import org.springframework.dao.DuplicateKeyException;
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

    public Assessment create(String userId, String organizationId, CreateAssessmentRequest request) {
        requireAuthor(userId, organizationId);
        if (new HashSet<>(request.questionIds()).size() != request.questionIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_QUESTION", "An assessment cannot repeat a question");
        }
        for (String questionId : request.questionIds()) {
            if (questions.findByIdAndOrganizationId(questionId, organizationId).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", "Question does not belong to this organization");
            }
        }
        return assessments.save(new Assessment(UUID.randomUUID().toString(), organizationId, userId,
                request.title().trim(), request.durationMinutes(), request.passingPercentage(),
                List.copyOf(request.questionIds()),
                request.resultReleasePolicy() == null ? ResultReleasePolicy.IMMEDIATE : request.resultReleasePolicy(),
                AssessmentStatus.DRAFT, 0, Instant.now(), null));
    }

    public List<Assessment> list(String userId, String organizationId) {
        requireAuthor(userId, organizationId);
        return assessments.findByOrganizationId(organizationId);
    }

    public AssessmentVersion publish(String userId, String organizationId, String assessmentId) {
        requireAuthor(userId, organizationId);
        Assessment assessment = assessments.findByIdAndOrganizationId(assessmentId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment not found"));
        if (assessment.status() != AssessmentStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PUBLISHED", "Assessment is already published");
        }
        List<AssessmentVersion.QuestionSnapshot> snapshots = new ArrayList<>();
        for (String questionId : assessment.questionIds()) {
            Question question = questions.findByIdAndOrganizationId(questionId, organizationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUESTION_MISSING", "Question is unavailable"));
            if (question.status() != QuestionStatus.APPROVED) {
                throw new ApiException(HttpStatus.CONFLICT, "QUESTION_NOT_APPROVED", "Approve every question before publishing");
            }
            snapshots.add(new AssessmentVersion.QuestionSnapshot(question.id(), question.topic(),
                    question.prompt(), question.type(), List.copyOf(question.options()),
                    java.util.Set.copyOf(question.correctOptionIds())));
        }
        int nextVersion = assessment.latestVersion() + 1;
        AssessmentVersion version = new AssessmentVersion(assessment.id() + ":" + nextVersion,
                organizationId, assessment.id(), nextVersion, assessment.title(),
                assessment.durationMinutes(), assessment.passingPercentage(), assessment.resultReleasePolicy(),
                List.copyOf(snapshots), Instant.now());
        String reservation = entitlements.reserve(organizationId, UsageMetric.PUBLISHED_ASSESSMENT);
        try {
            try {
                versions.insert(version);
            } catch (DuplicateKeyException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "PUBLISH_CONFLICT", "Assessment was published concurrently");
            }
            try {
                assessments.save(assessment.published(nextVersion));
            } catch (RuntimeException exception) {
                versions.deleteById(version.id());
                throw exception;
            }
            return version;
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
    }

    private void requireAuthor(String userId, String organizationId) {
        access.requireRole(userId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
    }
}
