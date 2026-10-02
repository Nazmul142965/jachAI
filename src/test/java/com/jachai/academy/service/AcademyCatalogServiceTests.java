package com.jachai.academy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.academy.dto.CreateBatchRequest;
import com.jachai.academy.dto.CreateCourseRequest;
import com.jachai.academy.model.Batch;
import com.jachai.academy.model.BatchEnrollment;
import com.jachai.academy.model.Course;
import com.jachai.academy.repository.BatchEnrollmentRepository;
import com.jachai.academy.repository.BatchRepository;
import com.jachai.academy.repository.CourseRepository;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AcademyCatalogServiceTests {
    @Mock CourseRepository courses;
    @Mock BatchRepository batches;
    @Mock BatchEnrollmentRepository enrollments;
    @Mock OrganizationRepository organizations;
    @Mock UserRepository users;
    @Mock TenantAccessService access;
    AcademyCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AcademyCatalogService(courses, batches, enrollments, organizations, users, access);
    }

    @Test
    void createsCourseWithTenantScopedNormalizedUniqueKey() {
        academy();
        when(courses.insert(any(Course.class))).thenAnswer(call -> call.getArgument(0));

        var view = service.createCourse("teacher", "org-a",
                new CreateCourseRequest("  Advanced   Java  ", "  Backend engineering  "));

        assertEquals("Advanced Java", view.name());
        ArgumentCaptor<Course> saved = ArgumentCaptor.forClass(Course.class);
        verify(courses).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
        assertEquals("advanced java", saved.getValue().nameKey());
        assertEquals("Backend engineering", saved.getValue().description());
        verify(access).requireRole("teacher", "org-a", OrganizationRole.ORG_ADMIN, OrganizationRole.INSTRUCTOR);
    }

    @Test
    void cannotCreateBatchInAnotherTenantsCourse() {
        academy();
        var request = new CreateBatchRequest("Fall cohort", Instant.now(), Instant.now().plusSeconds(3600));

        ApiException error = assertThrows(ApiException.class,
                () -> service.createBatch("teacher", "org-a", "foreign-course", request));

        assertEquals("NOT_FOUND", error.code());
        verify(courses).findByIdAndOrganizationId("foreign-course", "org-a");
        verify(batches, never()).insert(any(Batch.class));
    }

    @Test
    void rejectsInvalidBatchWindow() {
        academy();
        when(courses.findByIdAndOrganizationId("course-a", "org-a"))
                .thenReturn(Optional.of(course()));
        Instant start = Instant.now();

        ApiException error = assertThrows(ApiException.class, () -> service.createBatch(
                "teacher", "org-a", "course-a", new CreateBatchRequest("Fall", start, start)));

        assertEquals("INVALID_WINDOW", error.code());
    }

    @Test
    void enrollsOnlyVerifiedActiveLearnerIntoScopedBatch() {
        academy();
        when(courses.findByIdAndOrganizationId("course-a", "org-a"))
                .thenReturn(Optional.of(course()));
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(users.findById("learner")).thenReturn(Optional.of(user(true, false)));
        when(enrollments.insert(any(BatchEnrollment.class))).thenAnswer(call -> call.getArgument(0));

        var enrolled = service.enroll("teacher", "org-a", "course-a", "batch-a", "learner");

        assertEquals("Java", enrolled.courseName());
        assertEquals("Fall", enrolled.batchName());
        verify(access).requireRole("learner", "org-a", OrganizationRole.LEARNER);
        ArgumentCaptor<BatchEnrollment> saved = ArgumentCaptor.forClass(BatchEnrollment.class);
        verify(enrollments).insert(saved.capture());
        assertEquals("org-a", saved.getValue().organizationId());
    }

    @Test
    void disabledLearnerCannotBeEnrolled() {
        academy();
        when(courses.findByIdAndOrganizationId("course-a", "org-a"))
                .thenReturn(Optional.of(course()));
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(users.findById("learner")).thenReturn(Optional.of(user(true, true)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.enroll("teacher", "org-a", "course-a", "batch-a", "learner"));

        assertEquals("LEARNER_UNAVAILABLE", error.code());
        verify(enrollments, never()).insert(any(BatchEnrollment.class));
    }

    @Test
    void repeatEnrollmentReturnsExistingWithoutNewDocument() {
        academy();
        when(courses.findByIdAndOrganizationId("course-a", "org-a"))
                .thenReturn(Optional.of(course()));
        when(batches.findByIdAndOrganizationIdAndCourseId("batch-a", "org-a", "course-a"))
                .thenReturn(Optional.of(batch()));
        when(users.findById("learner")).thenReturn(Optional.of(user(true, false)));
        when(enrollments.findByOrganizationIdAndBatchIdAndLearnerUserId("org-a", "batch-a", "learner"))
                .thenReturn(Optional.of(enrollment()));

        var result = service.enroll("teacher", "org-a", "course-a", "batch-a", "learner");

        assertEquals("batch-a", result.batchId());
        verify(enrollments, never()).insert(any(BatchEnrollment.class));
    }

    @Test
    void learnerSeesOnlyOwnTenantScopedEnrollments() {
        academy();
        var pageable = PageRequest.of(0, 20);
        when(enrollments.findByOrganizationIdAndLearnerUserIdAndActiveTrue("org-a", "learner", pageable))
                .thenReturn(new PageImpl<>(java.util.List.of(enrollment()), pageable, 1));

        var result = service.myBatches("learner", "org-a", pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("Fall", result.getContent().getFirst().batchName());
        verify(access).requireRole("learner", "org-a", OrganizationRole.LEARNER);
    }

    @Test
    void talentWorkspaceCannotCreateAcademyCourse() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Company", OrganizationType.TALENT, "owner", Instant.now())));

        ApiException error = assertThrows(ApiException.class, () -> service.createCourse(
                "owner", "org-a", new CreateCourseRequest("Java", "")));

        assertEquals("ACADEMY_ONLY", error.code());
        verify(courses, never()).insert(any(Course.class));
    }

    private void academy() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Institute", OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    private Course course() {
        return new Course("course-a", "org-a", "Java", "java", "Course", "teacher", Instant.now());
    }

    private Batch batch() {
        return new Batch("batch-a", "org-a", "course-a", "Fall", "fall", Instant.now(),
                Instant.now().plusSeconds(3600), "teacher", Instant.now());
    }

    private BatchEnrollment enrollment() {
        return new BatchEnrollment("enrollment-a", "org-a", "course-a", "batch-a", "Java", "Fall",
                "learner", true, Instant.now());
    }

    private User user(boolean verified, boolean disabled) {
        return new User("learner", "learner@example.com", "Learner", "hash", verified, disabled,
                0, Instant.now());
    }
}
