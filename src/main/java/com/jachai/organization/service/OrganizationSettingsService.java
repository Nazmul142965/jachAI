package com.jachai.organization.service;

import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.organization.dto.OrganizationSettingsView;
import com.jachai.organization.dto.UpdateOrganizationSettingsRequest;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationSettings;
import com.jachai.organization.repository.OrganizationSettingsRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrganizationSettingsService {
    private final OrganizationSettingsRepository settings;
    private final TenantAccessService access;

    public OrganizationSettingsView get(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
        return settings.findById(organizationId).map(this::view)
                .orElse(new OrganizationSettingsView(ResultReleasePolicy.IMMEDIATE, null, null));
    }

    public OrganizationSettingsView update(String actorId, String organizationId,
                                            UpdateOrganizationSettingsRequest request) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        OrganizationSettings current = settings.findById(organizationId).orElse(null);
        OrganizationSettings changed = current == null
                ? new OrganizationSettings(organizationId, request.defaultResultReleasePolicy(),
                        actorId, Instant.now(), null)
                : current.withPolicy(request.defaultResultReleasePolicy(), actorId, Instant.now());
        try {
            return view(settings.save(changed));
        } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTINGS_CONFLICT", "Settings changed concurrently; reload and retry");
        }
    }

    public ResultReleasePolicy defaultPolicy(String organizationId) {
        return settings.findById(organizationId)
                .map(OrganizationSettings::defaultResultReleasePolicy)
                .orElse(ResultReleasePolicy.IMMEDIATE);
    }

    private OrganizationSettingsView view(OrganizationSettings value) {
        return new OrganizationSettingsView(value.defaultResultReleasePolicy(),
                value.updatedByUserId(), value.updatedAt());
    }
}
