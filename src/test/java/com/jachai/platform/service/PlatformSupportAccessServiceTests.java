package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.platform.dto.AssessmentSupportPreviewRequest;
import com.jachai.platform.model.PlatformSupportAccessEvent;
import com.jachai.platform.repository.PlatformSupportAccessEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PlatformSupportAccessServiceTests {
    @Mock PlatformAdminService platform;
    @Mock OrganizationRepository organizations;
    @Mock AssessmentRepository assessments;
    @Mock PlatformSupportAccessEventRepository events;
    @Mock TenantAccessService tenantAccess;

    private PlatformSupportAccessService service() {
        return new PlatformSupportAccessService(platform, organizations, assessments, events, tenantAccess);
    }

    private AssessmentSupportPreviewRequest request() {
        return new AssessmentSupportPreviewRequest("assessment-1", "CASE-12345",
                "Customer requested assessment publication support", true);
    }

    private Assessment assessment() {
        return new Assessment("assessment-1", "org-a", "author", "Java screening", 30, 60,
                List.of("q1", "q2"), ResultReleasePolicy.MANUAL, AssessmentStatus.PUBLISHED,
                2, Instant.now(), 3L, "Private instructions");
    }

    @Test
    void platformPreviewIsAuditedBeforeReturningNarrowMetadata() {
        when(platform.isPlatformAdmin("support")).thenReturn(true);
        when(organizations.existsById("org-a")).thenReturn(true);
        when(assessments.findByIdAndOrganizationId("assessment-1", "org-a"))
                .thenReturn(Optional.of(assessment()));
        when(events.insert(any(PlatformSupportAccessEvent.class))).thenAnswer(call -> call.getArgument(0));

        var preview = service().preview("support", "org-a", request());

        assertEquals("Java screening", preview.title());
        assertEquals(2, preview.latestVersion());
        assertEquals(2, preview.questionCount());
        var saved = ArgumentCaptor.forClass(PlatformSupportAccessEvent.class);
        verify(events).insert(saved.capture());
        assertEquals("CASE-12345", saved.getValue().ticketReference());
        assertEquals("support", saved.getValue().actorUserId());
        assertEquals(saved.getValue().id(), preview.accessEventId());
    }

    @Test
    void auditFailurePreventsTenantContentDisclosure() {
        when(platform.isPlatformAdmin("support")).thenReturn(true);
        when(organizations.existsById("org-a")).thenReturn(true);
        when(assessments.findByIdAndOrganizationId("assessment-1", "org-a"))
                .thenReturn(Optional.of(assessment()));
        when(events.insert(any(PlatformSupportAccessEvent.class)))
                .thenThrow(new IllegalStateException("audit unavailable"));

        assertThrows(IllegalStateException.class, () -> service().preview("support", "org-a", request()));
    }

    @Test
    void nonAdminCannotPreviewOrWriteAnAuditEvent() {
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service().preview("outsider", "org-a", request())).code());
        verify(assessments, never()).findByIdAndOrganizationId(any(), any());
        verify(events, never()).insert(any(PlatformSupportAccessEvent.class));
    }

    @Test
    void anotherTenantsAssessmentCannotBePreviewedOrAudited() {
        when(platform.isPlatformAdmin("support")).thenReturn(true);
        when(organizations.existsById("org-b")).thenReturn(true);

        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().preview("support", "org-b", request())).code());
        verify(assessments).findByIdAndOrganizationId("assessment-1", "org-b");
        verify(events, never()).insert(any(PlatformSupportAccessEvent.class));
    }

    @Test
    void tenantAdminCanReadOnlyTheirSupportAuditPage() {
        when(events.findByOrganizationId(org.mockito.ArgumentMatchers.eq("org-a"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new PlatformSupportAccessEvent("event-1", "org-a",
                        "assessment-1", "support", "CASE-12345", "Customer requested support",
                        Instant.now()))));

        var page = service().tenantHistory("owner", "org-a", 0);

        assertEquals(1, page.getTotalElements());
        assertEquals("event-1", page.getContent().getFirst().eventId());
        verify(tenantAccess).requireRole("owner", "org-a", OrganizationRole.ORG_ADMIN);
        verify(events).findByOrganizationId(org.mockito.ArgumentMatchers.eq("org-a"), any(Pageable.class));
        assertTrue(page.getContent().getFirst().reason().contains("support"));
    }
}
