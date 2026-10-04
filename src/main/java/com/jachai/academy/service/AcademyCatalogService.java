package com.jachai.academy.service;

import com.jachai.academy.dto.BatchView;
import com.jachai.academy.dto.CourseView;
import com.jachai.academy.dto.CreateBatchRequest;
import com.jachai.academy.dto.CreateCourseRequest;
import com.jachai.academy.dto.EnrollmentView;
import com.jachai.academy.model.Batch;
import com.jachai.academy.model.BatchEnrollment;
import com.jachai.academy.model.Course;
import com.jachai.academy.repository.BatchEnrollmentRepository;
import com.jachai.academy.repository.BatchRepository;
import com.jachai.academy.repository.CourseRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AcademyCatalogService {
    private final CourseRepository courses;
    private final BatchRepository batches;
    private final BatchEnrollmentRepository enrollments;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final TenantAccessService access;
    private final BatchAssessmentService batchAssessmentService;

    public CourseView createCourse(String actorId, String organizationId, CreateCourseRequest request) {
        requireStaff(actorId, organizationId);
        String name = canonicalName(request.name());
        Course course = new Course(UUID.randomUUID().toString(), organizationId, name, nameKey(name),
                request.description() == null ? "" : request.description().trim(), actorId, Instant.now());
        try {
            return CourseView.from(courses.insert(course));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "COURSE_EXISTS", "A course with this name already exists");
        }
    }

    public Page<CourseView> listCourses(String actorId, String organizationId, Pageable pageable) {
        requireAcademyMember(actorId, organizationId);
        return courses.findByOrganizationId(organizationId, pageable).map(CourseView::from);
    }

    public BatchView createBatch(String actorId, String organizationId, String courseId,
                                 CreateBatchRequest request) {
        requireStaff(actorId, organizationId);
        Course course = course(organizationId, courseId);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW", "Batch end must follow start");
        }
        String name = canonicalName(request.name());
        Batch batch = new Batch(UUID.randomUUID().toString(), organizationId, course.id(), name,
                nameKey(name), request.startsAt(), request.endsAt(), actorId, Instant.now());
        try {
            return BatchView.from(batches.insert(batch));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_EXISTS", "A batch with this name already exists in the course");
        }
    }

    public Page<BatchView> listBatches(String actorId, String organizationId, String courseId,
                                       Pageable pageable) {
        requireAcademyMember(actorId, organizationId);
        course(organizationId, courseId);
        return batches.findByOrganizationIdAndCourseId(organizationId, courseId, pageable)
                .map(BatchView::from);
    }

    public EnrollmentView enroll(String actorId, String organizationId, String courseId,
                                 String batchId, String learnerUserId) {
        requireStaff(actorId, organizationId);
        Course course = course(organizationId, courseId);
        Batch batch = batch(organizationId, courseId, batchId);
        access.requireRole(learnerUserId, organizationId, OrganizationRole.LEARNER);
        var learner = users.findById(learnerUserId)
                .filter(user -> user.emailVerified() && !user.disabled())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "LEARNER_UNAVAILABLE",
                        "Learner account is unavailable"));
        BatchEnrollment existing = enrollments.findByOrganizationIdAndBatchIdAndLearnerUserId(
                organizationId, batchId, learnerUserId).orElse(null);
        if (existing != null) {
            if (!existing.active()) {
                throw new ApiException(HttpStatus.CONFLICT, "ENROLLMENT_INACTIVE", "Enrollment is inactive");
            }
            batchAssessmentService.syncLearner(existing);
            return EnrollmentView.from(existing, learner.displayName());
        }
        BatchEnrollment enrollment = new BatchEnrollment(UUID.randomUUID().toString(), organizationId,
                courseId, batchId, course.name(), batch.name(), learnerUserId, true, Instant.now());
        BatchEnrollment saved;
        try {
            saved = enrollments.insert(enrollment);
        } catch (DuplicateKeyException exception) {
            saved = enrollments.findByOrganizationIdAndBatchIdAndLearnerUserId(
                            organizationId, batchId, learnerUserId)
                    .filter(BatchEnrollment::active)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ENROLLMENT_CONFLICT",
                            "Enrollment changed concurrently; reload it"));
        }
        batchAssessmentService.syncLearner(saved);
        return EnrollmentView.from(saved, learner.displayName());
    }

    public Page<EnrollmentView> listBatchLearners(String actorId, String organizationId,
                                                   String courseId, String batchId, Pageable pageable) {
        requireStaff(actorId, organizationId);
        batch(organizationId, courseId, batchId);
        Page<BatchEnrollment> page = enrollments.findByOrganizationIdAndBatchIdAndActiveTrue(
                organizationId, batchId, pageable);
        Map<String, String> names = new HashMap<>();
        if (page.hasContent()) {
            users.findAllById(page.getContent().stream().map(BatchEnrollment::learnerUserId).toList())
                    .forEach(user -> names.put(user.id(), user.displayName()));
        }
        return page.map(enrollment -> EnrollmentView.from(enrollment,
                names.get(enrollment.learnerUserId())));
    }

    public Page<EnrollmentView> myBatches(String learnerId, String organizationId, Pageable pageable) {
        access.requireRole(learnerId, organizationId, OrganizationRole.LEARNER);
        requireAcademy(organizationId);
        String displayName = users.findById(learnerId).map(user -> user.displayName()).orElse(null);
        return enrollments.findByOrganizationIdAndLearnerUserIdAndActiveTrue(
                organizationId, learnerId, pageable)
                .map(enrollment -> EnrollmentView.from(enrollment, displayName));
    }

    private Course course(String organizationId, String courseId) {
        return courses.findByIdAndOrganizationId(courseId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Course not found"));
    }

    private Batch batch(String organizationId, String courseId, String batchId) {
        return batches.findByIdAndOrganizationIdAndCourseId(batchId, organizationId, courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found"));
    }

    private void requireStaff(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.INSTRUCTOR);
        requireAcademy(organizationId);
    }

    private void requireAcademyMember(String actorId, String organizationId) {
        access.requireMember(actorId, organizationId);
        requireAcademy(organizationId);
    }

    private void requireAcademy(String organizationId) {
        var organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.ACADEMY) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMY_ONLY", "Courses are available in Academy organizations");
        }
    }

    private String canonicalName(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }

    private String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
