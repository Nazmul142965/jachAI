package com.jachai.organization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.organization.dto.UpdateOrganizationSettingsRequest;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationSettings;
import com.jachai.organization.repository.OrganizationSettingsRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class OrganizationSettingsServiceTests {
    @Mock OrganizationSettingsRepository settings;
    @Mock TenantAccessService access;

    private OrganizationSettingsService service() {
        return new OrganizationSettingsService(settings, access);
    }

    @Test
    void absentSettingsReadAsImmediateWithoutWritingDocument() {
        var view = service().get("teacher", "org-a");

        assertEquals(ResultReleasePolicy.IMMEDIATE, view.defaultResultReleasePolicy());
        assertNull(view.updatedAt());
        verify(access).requireRole("teacher", "org-a", OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
    }

    @Test
    void onlyAdminMayUpdateAndPolicyIsScopedByOrganizationId() {
        when(settings.save(any(OrganizationSettings.class))).thenAnswer(call -> call.getArgument(0));

        var view = service().update("admin", "org-a",
                new UpdateOrganizationSettingsRequest(ResultReleasePolicy.MANUAL));

        assertEquals(ResultReleasePolicy.MANUAL, view.defaultResultReleasePolicy());
        verify(access).requireRole("admin", "org-a", OrganizationRole.ORG_ADMIN);
        verify(settings).findById("org-a");
    }

    @Test
    void assessmentDefaultReadsPersistedOrganizationPolicy() {
        when(settings.findById("org-a")).thenReturn(Optional.of(new OrganizationSettings(
                "org-a", ResultReleasePolicy.MANUAL, "admin", Instant.now(), 1L)));

        assertEquals(ResultReleasePolicy.MANUAL, service().defaultPolicy("org-a"));
    }

    @Test
    void concurrentSettingsChangeReturnsConflict() {
        when(settings.findById("org-a")).thenReturn(Optional.of(new OrganizationSettings(
                "org-a", ResultReleasePolicy.IMMEDIATE, "admin", Instant.now(), 1L)));
        when(settings.save(any(OrganizationSettings.class)))
                .thenThrow(new OptimisticLockingFailureException("changed"));

        ApiException error = assertThrows(ApiException.class, () -> service().update("admin", "org-a",
                new UpdateOrganizationSettingsRequest(ResultReleasePolicy.MANUAL)));
        assertEquals("SETTINGS_CONFLICT", error.code());
    }
}
