package com.jachai.platform.service;

import com.jachai.assessment.repository.AssessmentRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.platform.dto.AssessmentSupportPreview;
import com.jachai.platform.dto.AssessmentSupportPreviewRequest;
import com.jachai.platform.dto.SupportAccessAuditView;
import com.jachai.platform.model.PlatformSupportAccessEvent;
import com.jachai.platform.repository.PlatformSupportAccessEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformSupportAccessService {
    private static final int PAGE_SIZE = 20;
    private final PlatformAdminService platform;
    private final OrganizationRepository organizations;
    private final AssessmentRepository assessments;
    private final PlatformSupportAccessEventRepository events;
    private final TenantAccessService tenantAccess;

    public AssessmentSupportPreview preview(String actorUserId, String organizationId,
                                            AssessmentSupportPreviewRequest request) {
        requirePlatformAdmin(actorUserId);
        requireOrganization(organizationId);
        var assessment = assessments.findByIdAndOrganizationId(request.assessmentId(), organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Assessment not found"));
        // A failed audit write must prevent disclosure. No tenant content is returned before insert.
        var event = events.insert(new PlatformSupportAccessEvent(UUID.randomUUID().toString(),
                organizationId, assessment.id(), actorUserId, request.ticketReference().trim(),
                request.reason().trim(), Instant.now()));
        return new AssessmentSupportPreview(organizationId, assessment.id(), assessment.title(),
                assessment.status(), assessment.latestVersion(), assessment.questionIds().size(),
                assessment.durationMinutes(), event.id());
    }

    public Page<SupportAccessAuditView> platformHistory(String actorUserId,
                                                            String organizationId, int page) {
        requirePlatformAdmin(actorUserId);
        requireOrganization(organizationId);
        return history(organizationId, page);
    }

    public Page<SupportAccessAuditView> tenantHistory(String actorUserId,
                                                          String organizationId, int page) {
        tenantAccess.requireRole(actorUserId, organizationId, OrganizationRole.ORG_ADMIN);
        return history(organizationId, page);
    }

    private Page<SupportAccessAuditView> history(String organizationId, int page) {
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        return events.findByOrganizationId(organizationId,
                PageRequest.of(page, PAGE_SIZE,
                        Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))))
                .map(SupportAccessAuditView::from);
    }

    private void requirePlatformAdmin(String actorUserId) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
    }

    private void requireOrganization(String organizationId) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found");
        }
    }
}
