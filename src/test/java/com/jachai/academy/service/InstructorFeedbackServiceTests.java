package com.jachai.academy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.academy.dto.WriteFeedbackRequest;
import com.jachai.academy.model.FeedbackStatus;
import com.jachai.academy.model.InstructorFeedback;
import com.jachai.academy.repository.InstructorFeedbackRepository;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InstructorFeedbackServiceTests {
    @Mock InstructorFeedbackRepository feedback;
    @Mock AttemptRepository attempts;
    @Mock OrganizationRepository organizations;
    @Mock PublishedVersionStore versions;
    @Mock TenantAccessService access;
    InstructorFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new InstructorFeedbackService(feedback, attempts, organizations, versions, access);
    }

    @Test
    void instructorWritesTenantScopedDraftForSubmittedAttempt() {
        academy();
        when(attempts.findByIdAndOrganizationId("a1", "academy-a"))
                .thenReturn(Optional.of(attempt(null)));
        when(feedback.insert(any(InstructorFeedback.class))).thenAnswer(call -> call.getArgument(0));

        var result = service.writeDraft("teacher", "academy-a", "a1",
                new WriteFeedbackRequest("  Good foundations  ", "  Practice concurrency  "));

        assertEquals(FeedbackStatus.DRAFT, result.status());
        assertEquals("learner", result.learnerUserId());
        assertEquals("Good foundations", result.summary());
        verify(access).requireRole("teacher", "academy-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR);
        verify(attempts).findByIdAndOrganizationId("a1", "academy-a");
    }

    @Test
    void crossTenantAttemptCannotReceiveFeedback() {
        academy();

        ApiException error = assertThrows(ApiException.class, () -> service.writeDraft(
                "teacher", "academy-a", "foreign-attempt", new WriteFeedbackRequest("Good", "Practice")));

        assertEquals("NOT_FOUND", error.code());
        verify(feedback, never()).insert(any(InstructorFeedback.class));
    }

    @Test
    void learnerCannotReadAnotherLearnersFeedback() {
        academy();

        ApiException error = assertThrows(ApiException.class,
                () -> service.learnerView("other-learner", "academy-a", "a1"));

        assertEquals("NOT_FOUND", error.code());
        verify(attempts).findByIdAndOrganizationIdAndUserId("a1", "academy-a", "other-learner");
        verify(feedback, never()).findByOrganizationIdAndAttemptIdAndLearnerUserId(any(), any(), any());
    }

    @Test
    void draftIsHiddenFromLearner() {
        academy();
        when(attempts.findByIdAndOrganizationIdAndUserId("a1", "academy-a", "learner"))
                .thenReturn(Optional.of(attempt(null)));
        when(feedback.findByOrganizationIdAndAttemptIdAndLearnerUserId("academy-a", "a1", "learner"))
                .thenReturn(Optional.of(draft()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.learnerView("learner", "academy-a", "a1"));

        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void manualResultMustBeReleasedBeforePublishing() {
        academy();
        when(attempts.findByIdAndOrganizationId("a1", "academy-a"))
                .thenReturn(Optional.of(attempt(null)));
        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft()));
        when(versions.get("academy-a", "v1")).thenReturn(version(ResultReleasePolicy.MANUAL));

        ApiException error = assertThrows(ApiException.class,
                () -> service.publish("teacher", "academy-a", "a1"));

        assertEquals("RESULT_NOT_RELEASED", error.code());
        verify(feedback, never()).save(any());
    }

    @Test
    void publishedFeedbackIsVisibleToItsLearnerAndImmutable() {
        academy();
        when(attempts.findByIdAndOrganizationId("a1", "academy-a"))
                .thenReturn(Optional.of(attempt(Instant.now())));
        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft()));
        when(feedback.save(any(InstructorFeedback.class))).thenAnswer(call -> call.getArgument(0));

        var published = service.publish("teacher", "academy-a", "a1");
        assertEquals(FeedbackStatus.PUBLISHED, published.status());

        when(attempts.findByIdAndOrganizationIdAndUserId("a1", "academy-a", "learner"))
                .thenReturn(Optional.of(attempt(Instant.now())));
        when(feedback.findByOrganizationIdAndAttemptIdAndLearnerUserId("academy-a", "a1", "learner"))
                .thenReturn(Optional.of(draft().publish(Instant.now())));
        assertEquals("Good work", service.learnerView("learner", "academy-a", "a1").summary());

        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft().publish(Instant.now())));
        ApiException error = assertThrows(ApiException.class, () -> service.writeDraft(
                "teacher", "academy-a", "a1", new WriteFeedbackRequest("Changed", "Changed")));
        assertEquals("FEEDBACK_PUBLISHED", error.code());
    }

    @Test
    void talentOrganizationCannotUseAcademyFeedback() {
        when(organizations.findById("talent-a")).thenReturn(Optional.of(new Organization(
                "talent-a", "Company", OrganizationType.TALENT, "owner", Instant.now())));

        ApiException error = assertThrows(ApiException.class, () -> service.staffView(
                "recruiter", "talent-a", "a1"));

        assertEquals("ACADEMY_ONLY", error.code());
        verify(feedback, never()).findByOrganizationIdAndAttemptId(any(), any());
    }

    private void academy() {
        when(organizations.findById("academy-a")).thenReturn(Optional.of(new Organization(
                "academy-a", "Institute", OrganizationType.ACADEMY, "owner", Instant.now())));
    }

    private Attempt attempt(Instant releasedAt) {
        return new Attempt("a1", "academy-a", "assignment", "v1", "learner", 1,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300),
                AttemptStatus.SUBMITTED, Map.of(), Instant.now().minusSeconds(300),
                80, true, releasedAt, 0L);
    }

    private InstructorFeedback draft() {
        return new InstructorFeedback("f1", "academy-a", "a1", "learner", "teacher",
                "Good work", "Practice more", FeedbackStatus.DRAFT,
                Instant.now(), Instant.now(), null, 0L);
    }

    private AssessmentVersion version(ResultReleasePolicy policy) {
        return new AssessmentVersion("v1", "academy-a", "assessment", 1, "Test",
                30, 60, policy, List.of(), Instant.now());
    }
}
