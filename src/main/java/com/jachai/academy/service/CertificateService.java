package com.jachai.academy.service;

import com.jachai.academy.dto.CertificateVerificationView;
import com.jachai.academy.dto.CertificateAuditView;
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
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
public class CertificateService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);

    private final CertificateRepository certificates;
    private final AttemptRepository attempts;
    private final OrganizationRepository organizations;
    private final PublishedVersionStore versions;
    private final TenantAccessService access;
    private final EntitlementService entitlements;
    private final AuditEventRepository auditEvents;
    private final UserRepository users;
    private final EmailSender emailSender;
    @Value("${app.public-base-url}") private String publicBaseUrl;

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
            ensureAudit(existing.get());
            if (existing.get().status() == CertificateStatus.REVOKED) {
                throw new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_REVOKED", "A revoked certificate cannot be reissued");
            }
            queueIssuedEmail(existing.get());
            return CertificateView.from(existing.get());
        }
        byte[] token = new byte[24];
        RANDOM.nextBytes(token);
        Certificate certificate = new Certificate(UUID.randomUUID().toString(), organizationId,
                attempt.userId(), attempt.id(), attempt.assessmentVersionId(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                CertificateStatus.ISSUED, Instant.now(), null, actorId, null, null);
        String reservation = entitlements.reserve(organizationId, UsageMetric.CERTIFICATE);
        Certificate saved;
        try {
            saved = certificates.insert(certificate);
        } catch (DuplicateKeyException exception) {
            entitlements.refund(reservation);
            saved = certificates.findByAttemptIdAndOrganizationId(attemptId, organizationId)
                    .filter(found -> found.status() == CertificateStatus.ISSUED)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CERTIFICATE_CONFLICT",
                            "Certificate was created concurrently; retry"));
        } catch (RuntimeException exception) {
            entitlements.refund(reservation);
            throw exception;
        }
        ensureAudit(saved);
        queueIssuedEmail(saved);
        return CertificateView.from(saved);
    }

    private void queueIssuedEmail(Certificate certificate) {
        try {
            users.findById(certificate.learnerUserId()).ifPresent(user ->
                    emailSender.sendCertificateIssued(certificate.id(), user.email(), user.displayName(),
                            publicBaseUrl.replaceAll("/+$", "") + "/certificates/" + certificate.verificationCode()));
        } catch (RuntimeException exception) {
            // Certificate state is independent of notification delivery. A retry repairs a missed job.
            log.warn("Certificate email enqueue failed for certificate {}: {}",
                    certificate.id(), exception.getClass().getSimpleName());
        }
    }

    public CertificateView revoke(String actorId, String organizationId, String certificateId) {
        requireAcademyStaff(actorId, organizationId);
        Certificate certificate = certificates.findByIdAndOrganizationId(certificateId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Certificate not found"));
        if (certificate.status() == CertificateStatus.REVOKED) {
            ensureAudit(certificate);
            return CertificateView.from(certificate);
        }
        try {
            Certificate saved = certificates.save(certificate.revoke(Instant.now(), actorId));
            ensureAudit(saved);
            return CertificateView.from(saved);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "REVOKE_CONFLICT", "Certificate changed concurrently; reload it");
        }
    }

    public Page<CertificateAuditView> audit(String actorId, String organizationId,
                                            String certificateId, int page) {
        requireAcademyStaff(actorId, organizationId);
        certificates.findByIdAndOrganizationId(certificateId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Certificate not found"));
        return auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                organizationId, "CERTIFICATE", certificateId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.ASC, "occurredAt")))
                .map(CertificateAuditView::from);
    }

    private void ensureAudit(Certificate certificate) {
        if (certificate.issuedByUserId() != null) {
            insertAudit(certificate, AuditAction.CERTIFICATE_ISSUED,
                    certificate.issuedByUserId(), certificate.issuedAt());
        }
        if (certificate.status() == CertificateStatus.REVOKED
                && certificate.revokedByUserId() != null) {
            insertAudit(certificate, AuditAction.CERTIFICATE_REVOKED,
                    certificate.revokedByUserId(), certificate.revokedAt());
        }
    }

    private void insertAudit(Certificate certificate, AuditAction action,
                             String actorId, Instant occurredAt) {
        AuditEvent event = new AuditEvent("certificate:" + certificate.id() + ":" + action,
                certificate.organizationId(), actorId, action, "CERTIFICATE",
                certificate.id(), occurredAt);
        try {
            auditEvents.insert(event);
        } catch (DuplicateKeyException ignored) {
            // A concurrent retry recorded the same immutable transition.
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
