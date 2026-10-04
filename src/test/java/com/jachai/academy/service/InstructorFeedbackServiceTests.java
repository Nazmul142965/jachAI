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
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.shared.audit.AuditAction;
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
    @Mock UserRepository users;
    @Mock EmailSender emailSender;
    @Mock AuditEventRepository auditEvents;
    InstructorFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new InstructorFeedbackService(feedback, attempts, organizations, versions, access,
                users, emailSender, auditEvents, "https://jachai.test");
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
    void ungradedImmediatePolicyResultCannotPublishFeedback() {
        academy();
        Attempt pending = new Attempt("a1", "academy-a", "assignment", "v1", "learner", 1,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300),
                AttemptStatus.SUBMITTED, Map.of(), Instant.now().minusSeconds(300),
                null, null, null, 0L);
        when(attempts.findByIdAndOrganizationId("a1", "academy-a"))
                .thenReturn(Optional.of(pending));
        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft()));

        assertEquals("RESULT_NOT_RELEASED", assertThrows(ApiException.class,
                () -> service.publish("teacher", "academy-a", "a1")).code());
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
                .thenReturn(Optional.of(draft().publish("teacher", Instant.now())));
        assertEquals("Good work", service.learnerView("learner", "academy-a", "a1").summary());

        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft().publish("teacher", Instant.now())));
        ApiException error = assertThrows(ApiException.class, () -> service.writeDraft(
                "teacher", "academy-a", "a1", new WriteFeedbackRequest("Changed", "Changed")));
        assertEquals("FEEDBACK_PUBLISHED", error.code());
    }

    @Test
    void publishedFeedbackQueuesLearnerNoticeAndRetryRepairsEnqueue() {
        academy();
        when(attempts.findByIdAndOrganizationId("a1", "academy-a"))
                .thenReturn(Optional.of(attempt(Instant.now())));
        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft()), Optional.of(draft().publish("publisher", Instant.now())));
        when(feedback.save(any(InstructorFeedback.class))).thenAnswer(call -> call.getArgument(0));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner",
                "learner@example.com", "Learner", null, true, false, 0, Instant.now())));
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox unavailable"))
                .doNothing().when(emailSender).sendFeedbackPublished("f1", "learner@example.com",
                        "Learner", "https://jachai.test/");

        service.publish("publisher", "academy-a", "a1");
        service.publish("publisher", "academy-a", "a1");

        verify(emailSender, org.mockito.Mockito.times(2)).sendFeedbackPublished("f1",
                "learner@example.com", "Learner", "https://jachai.test/");
        verify(feedback, org.mockito.Mockito.times(1)).save(any(InstructorFeedback.class));
        verify(auditEvents, org.mockito.Mockito.times(2)).insert(org.mockito.ArgumentMatchers.<AuditEvent>argThat(
                event -> event.action() == AuditAction.FEEDBACK_PUBLISHED
                        && event.organizationId().equals("academy-a")
                        && event.targetId().equals("a1")
                        && event.actorUserId().equals("publisher")));
    }

    @Test
    void feedbackAuditIsTenantScopedAndStaffOnly() {
        academy();
        when(feedback.findByOrganizationIdAndAttemptId("academy-a", "a1"))
                .thenReturn(Optional.of(draft().publish("teacher", Instant.now())));
        when(auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                org.mockito.ArgumentMatchers.eq("academy-a"),
                org.mockito.ArgumentMatchers.eq("FEEDBACK"),
                org.mockito.ArgumentMatchers.eq("a1"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertEquals(0, service.audit("teacher", "academy-a", "a1", 0).getTotalElements());
        verify(access).requireRole("teacher", "academy-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR);
        assertEquals("INVALID_PAGE", assertThrows(ApiException.class,
                () -> service.audit("teacher", "academy-a", "a1", -1)).code());
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
