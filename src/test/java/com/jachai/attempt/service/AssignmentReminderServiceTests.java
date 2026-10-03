package com.jachai.attempt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentVersionRepository;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.repository.AssignmentRepository;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssignmentReminderServiceTests {
    @Mock AssignmentRepository assignments;
    @Mock AssessmentVersionRepository versions;
    @Mock AttemptRepository attempts;
    @Mock UserRepository users;
    @Mock TenantAccessService access;
    @Mock EmailSender emailSender;

    private AssignmentReminderService service() {
        var service = new AssignmentReminderService(assignments, versions, attempts, users,
                access, emailSender);
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://example.test/");
        return service;
    }

    @Test
    void staffQueuesReminderForActiveUnfinishedAssignment() {
        Instant now = Instant.now();
        Assignment assignment = new Assignment("as1", "org-a", "assessment", "assessment:1",
                null, null, "learner", 1, now.minusSeconds(60), now.plusSeconds(3600), now);
        when(assignments.findByIdAndOrganizationId("as1", "org-a")).thenReturn(Optional.of(assignment));
        when(users.findById("learner")).thenReturn(Optional.of(new User("learner", "learner@example.com",
                "Learner", null, true, false, 0, now)));
        when(versions.findByIdAndOrganizationId("assessment:1", "org-a"))
                .thenReturn(Optional.of(new AssessmentVersion("assessment:1", "org-a", "assessment",
                        1, "Java", 30, 60, ResultReleasePolicy.IMMEDIATE, List.of(), now)));
        when(emailSender.sendAssignmentReminder("as1", "learner@example.com", "Learner",
                "Java", "https://example.test/", assignment.availableUntil())).thenReturn(true);

        assertTrue(service().queue("teacher", "org-a", "as1"));

        verify(access).requireRole("teacher", "org-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        verify(access).requireRole("learner", "org-a", OrganizationRole.LEARNER,
                OrganizationRole.CANDIDATE);
    }

    @Test
    void expiredAssignmentCannotSendReminder() {
        Instant now = Instant.now();
        when(assignments.findByIdAndOrganizationId("as1", "org-a"))
                .thenReturn(Optional.of(new Assignment("as1", "org-a", "assessment", "assessment:1",
                        null, null, "learner", 1, now.minusSeconds(3600), now.minusSeconds(1), now)));

        ApiException error = assertThrows(ApiException.class, () -> service().queue("teacher", "org-a", "as1"));

        assertEquals("ASSIGNMENT_EXPIRED", error.code());
        verify(emailSender, never()).sendAssignmentReminder(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherTenantsAssignmentIsNotFound() {
        ApiException error = assertThrows(ApiException.class, () -> service().queue("teacher", "org-b", "as1"));

        assertEquals("NOT_FOUND", error.code());
        verify(assignments).findByIdAndOrganizationId("as1", "org-b");
        verify(emailSender, never()).sendAssignmentReminder(any(), any(), any(), any(), any(), any());
    }
}
