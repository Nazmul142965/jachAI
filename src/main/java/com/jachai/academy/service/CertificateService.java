package com.jachai.academy.service;

import com.jachai.academy.dto.CertificateVerificationView;
import com.jachai.academy.dto.CertificateView;
import com.jachai.academy.model.Certificate;
import com.jachai.academy.model.CertificateStatus;
import com.jachai.academy.repository.CertificateRepository;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.service.EntitlementService;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CertificateService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CertificateRepository certificates;
    private final AttemptRepository attempts;
    private final OrganizationRepository organizations;
    private final PublishedVersionStore versions;
    private final TenantAccessService access;
    private final EntitlementService entitlements;

    public List<CertificateView> mine(String learnerId, String organizationId) {
        access.requireRole(learnerId, organizationId, OrganizationRole.LEARNER);
        return certificates.findByOrganizationIdAndLearnerUserId(organizationId, learnerId).stream()
                .map(CertificateView::from).toList();
    }

    public CertificateView issue(String actorId, String organizationId, String attemptId) {
        requireAcademyStaff(actorId, organizationId);
        Attempt attempt = attempts.findByIdAndOrganizationId(attemptId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED || !Boolean.TRUE.equals(attempt.passed())) {
            throw new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_NOT_ELIGIBLE", "A passing final result is required");
        }
        AssessmentVersion version = versions.get(organizationId, attempt.assessmentVersionId());
        if (version.resultReleasePolicy() == ResultReleasePolicy.MANUAL && attempt.resultReleasedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_NOT_RELEASED", "Release the result before issuing a certificate");
        }
        var existing = certificates.findByAttemptIdAndOrganizationId(attemptId, organizationId);
        if (existing.isPresent()) {
            if (existing.get().status() == CertificateStatus.REVOKED) {
                throw new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_REVOKED", "A revoked certificate cannot be reissued");
            }
            return CertificateView.from(existing.get());
        }
        byte[] token = new byte[24];
        RANDOM.nextBytes(token);
        Certificate certificate = new Certificate(UUID.randomUUID().toString(), organizationId,
                attempt.userId(), attempt.id(), attempt.assessmentVersionId(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                CertificateStatus.ISSUED, Instant.now(), null, null);
        String reservation = entitlements.reserve(organizationId, UsageMetric.CERTIFICATE);
        try {
            return CertificateView.from(certificates.insert(certificate));
        } catch (DuplicateKeyException exception) {
            entitlements.refund(reservation);
            return certificates.findByAttemptIdAndOrganizationId(attemptId, organizationId)
                    .filter(saved -> saved.status() == CertificateStatus.ISSUED)
                    .map(CertificateView::from)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_CONFLICT",
                            "Certificate was created concurrently; retry"));
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
    }

    public CertificateView revoke(String actorId, String organizationId, String certificateId) {
        requireAcademyStaff(actorId, organizationId);
        Certificate certificate = certificates.findByIdAndOrganizationId(certificateId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Certificate not found"));
        if (certificate.status() == CertificateStatus.REVOKED) {
            return CertificateView.from(certificate);
        }
        try {
            return CertificateView.from(certificates.save(certificate.revoke(Instant.now())));
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "REVOKE_CONFLICT", "Certificate changed concurrently; reload it");
        }
    }

    public CertificateVerificationView verify(String verificationCode) {
        Certificate certificate = certificates.findByVerificationCode(verificationCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Certificate not found"));
        Organization organization = organizations.findById(certificate.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Certificate not found"));
        AssessmentVersion version = versions.get(certificate.organizationId(), certificate.assessmentVersionId());
        return new CertificateVerificationView(certificate.verificationCode(),
                certificate.status() == CertificateStatus.ISSUED,
                organization.name(), version.title(), certificate.issuedAt(), certificate.revokedAt());
    }

    private void requireAcademyStaff(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.INSTRUCTOR);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.ACADEMY) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMY_ONLY", "Certificates are available to Academy organizations");
        }
    }
}
