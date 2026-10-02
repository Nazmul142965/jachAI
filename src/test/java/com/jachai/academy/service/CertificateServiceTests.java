package com.jachai.academy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.jachai.academy.model.Certificate;
import com.jachai.academy.model.CertificateStatus;
import com.jachai.academy.repository.CertificateRepository;
import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.EmailSender;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.billing.service.EntitlementService;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import com.jachai.shared.audit.AuditEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CertificateServiceTests {
    @Mock CertificateRepository certificates;
    @Mock AttemptRepository attempts;
    @Mock OrganizationRepository organizations;
    @Mock PublishedVersionStore versions;
    @Mock TenantAccessService access;
    @Mock EntitlementService entitlements;
    @Mock AuditEventRepository auditEvents;
    @Mock UserRepository users;
    @Mock EmailSender emailSender;

    private CertificateService service() {
        CertificateService result = new CertificateService(certificates, attempts, organizations, versions, access,
                entitlements, auditEvents, users, emailSender);
        ReflectionTestUtils.setField(result, "publicBaseUrl", "https://jachai.example");
        return result;
    }

    private Organization academy() {
        return new Organization("org-a", "Example Academy", OrganizationType.ACADEMY, "owner", Instant.now());
    }

    private Attempt passed(Instant releasedAt) {
        return new Attempt("attempt", "org-a", "assignment", "a:1", "learner", 1,
                Instant.now().minusSeconds(100), Instant.now().minusSeconds(10), AttemptStatus.SUBMITTED,
                Map.of(), Instant.now(), 100, true, releasedAt, 1L);
    }

    private AssessmentVersion version(ResultReleasePolicy policy) {
        return new AssessmentVersion("a:1", "org-a", "a", 1, "Java Basics", 20, 60,
                policy, List.of(), Instant.now());
    }

    private Certificate issued() {
        return new Certificate("cert", "org-a", "learner", "attempt", "a:1", "random-code",
                CertificateStatus.ISSUED, Instant.now(), null, 1L);
    }

    @Test
    void passingReleasedAttemptCreatesVerifiableCertificate() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(passed(Instant.now())));
        when(versions.get("org-a", "a:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        when(certificates.insert(any(Certificate.class))).thenAnswer(call -> call.getArgument(0));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));

        var result = service().issue("teacher", "org-a", "attempt");

        assertNotNull(result.id());
        assertTrue(result.verificationCode().matches("[A-Za-z0-9_-]{32}"));
        assertEquals(CertificateStatus.ISSUED, result.status());
        verify(certificates).insert(any(Certificate.class));
        verify(auditEvents).insert(org.mockito.ArgumentMatchers.<AuditEvent>argThat(event ->
                event.action() == AuditAction.CERTIFICATE_ISSUED
                        && event.actorUserId().equals("teacher")
                        && event.organizationId().equals("org-a")));
        verify(emailSender).sendCertificateIssued(result.id(), "learner@example.com", "Learner",
                "https://jachai.example/certificates/" + result.verificationCode());
    }

    private User learner() {
        return new User("learner", "learner@example.com", "Learner", null,
                true, false, 0, Instant.now());
    }

    @Test
    void emailQueueFailureDoesNotUndoCertificate() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(passed(Instant.now())));
        when(versions.get("org-a", "a:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        when(certificates.insert(any(Certificate.class))).thenAnswer(call -> call.getArgument(0));
        when(users.findById("learner")).thenReturn(Optional.of(learner()));
        doThrow(new IllegalStateException("mail unavailable")).when(emailSender)
                .sendCertificateIssued(any(), any(), any(), any());

        assertEquals(CertificateStatus.ISSUED, service().issue("teacher", "org-a", "attempt").status());
        verify(certificates).insert(any(Certificate.class));
    }

    @Test
    void manualResultMustBeReleasedBeforeCertificate() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(passed(null)));
        when(versions.get("org-a", "a:1")).thenReturn(version(ResultReleasePolicy.MANUAL));

        ApiException error = assertThrows(ApiException.class,
                () -> service().issue("teacher", "org-a", "attempt"));

        assertEquals("RESULT_NOT_RELEASED", error.code());
        verify(certificates, never()).insert(any(Certificate.class));
    }

    @Test
    void cannotIssueForAnotherTenantAttempt() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));

        ApiException error = assertThrows(ApiException.class,
                () -> service().issue("teacher", "org-a", "foreign-attempt"));

        assertEquals("NOT_FOUND", error.code());
        verify(attempts).findByIdAndOrganizationId("foreign-attempt", "org-a");
        verify(certificates, never()).insert(any(Certificate.class));
    }

    @Test
    void issuedCertificateIsIdempotentAndCannotBeReissuedAfterRevocation() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(passed(Instant.now())));
        when(versions.get("org-a", "a:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        when(certificates.findByAttemptIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(issued()))
                .thenReturn(Optional.of(issued().revoke(Instant.now())));

        assertEquals("cert", service().issue("teacher", "org-a", "attempt").id());
        assertEquals("CERTIFICATE_REVOKED", assertThrows(ApiException.class,
                () -> service().issue("teacher", "org-a", "attempt")).code());
        verify(certificates, never()).insert(any(Certificate.class));
    }

    @Test
    void revokedCertificateVerifiesAsInvalidWithoutPrivateIdentity() {
        when(certificates.findByVerificationCode("random-code"))
                .thenReturn(Optional.of(issued().revoke(Instant.now())));
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(versions.get("org-a", "a:1")).thenReturn(version(ResultReleasePolicy.IMMEDIATE));

        var result = service().verify("random-code");

        assertFalse(result.valid());
        assertEquals("Example Academy", result.organizationName());
        assertEquals("Java Basics", result.assessmentTitle());
    }

    @Test
    void talentOrganizationsCannotIssueAcademyCertificates() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(new Organization(
                "org-a", "Example Talent", OrganizationType.TALENT, "owner", Instant.now())));

        ApiException error = assertThrows(ApiException.class,
                () -> service().issue("recruiter", "org-a", "attempt"));

        assertEquals("ACADEMY_ONLY", error.code());
        verify(attempts, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void learnerOnlySeesOwnTenantCertificates() {
        when(certificates.findByOrganizationIdAndLearnerUserId("org-a", "learner"))
                .thenReturn(List.of(issued()));

        var result = service().mine("learner", "org-a");

        assertEquals(1, result.size());
        assertEquals("random-code", result.getFirst().verificationCode());
        verify(certificates).findByOrganizationIdAndLearnerUserId("org-a", "learner");
    }

    @Test
    void revocationRecordsOriginalIssuerAndRevokingActor() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        Certificate original = new Certificate("cert", "org-a", "learner", "attempt", "a:1",
                "random-code", CertificateStatus.ISSUED, Instant.now(), null,
                "issuer", null, 1L);
        when(certificates.findByIdAndOrganizationId("cert", "org-a"))
                .thenReturn(Optional.of(original));
        when(certificates.save(any(Certificate.class))).thenAnswer(call -> call.getArgument(0));

        var result = service().revoke("teacher", "org-a", "cert");

        assertEquals(CertificateStatus.REVOKED, result.status());
        verify(auditEvents).insert(org.mockito.ArgumentMatchers.<AuditEvent>argThat(event ->
                event.action() == AuditAction.CERTIFICATE_ISSUED
                        && event.actorUserId().equals("issuer")));
        verify(auditEvents).insert(org.mockito.ArgumentMatchers.<AuditEvent>argThat(event ->
                event.action() == AuditAction.CERTIFICATE_REVOKED
                        && event.actorUserId().equals("teacher")));
    }

    @Test
    void auditHistoryIsStaffOnlyAndTenantScoped() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(certificates.findByIdAndOrganizationId("cert", "org-a"))
                .thenReturn(Optional.of(issued()));
        AuditEvent event = new AuditEvent("certificate:cert:CERTIFICATE_ISSUED", "org-a",
                "teacher", AuditAction.CERTIFICATE_ISSUED, "CERTIFICATE", "cert", Instant.now());
        when(auditEvents.findByOrganizationIdAndTargetTypeAndTargetId(
                "org-a", "CERTIFICATE", "cert", PageRequest.of(0, 20,
                        Sort.by(Sort.Direction.ASC, "occurredAt"))))
                .thenReturn(new PageImpl<>(List.of(event)));

        var result = service().audit("teacher", "org-a", "cert", 0);

        assertEquals(1, result.getTotalElements());
        assertEquals("teacher", result.getContent().getFirst().actorUserId());
        verify(access).requireRole("teacher", "org-a",
                com.jachai.organization.model.OrganizationRole.ORG_ADMIN,
                com.jachai.organization.model.OrganizationRole.INSTRUCTOR);
    }

    @Test
    void foreignCertificateAuditCannotBeRead() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));

        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> service().audit("teacher", "org-a", "foreign", 0)).code());
        verify(auditEvents, never()).findByOrganizationIdAndTargetTypeAndTargetId(
                any(), any(), any(), any());
    }

    @Test
    void retryRepairsAuditAfterCertificateWriteSucceeded() {
        when(organizations.findById("org-a")).thenReturn(Optional.of(academy()));
        when(attempts.findByIdAndOrganizationId("attempt", "org-a"))
                .thenReturn(Optional.of(passed(Instant.now())));
        when(versions.get("org-a", "a:1")).thenReturn(version(ResultReleasePolicy.MANUAL));
        doThrow(new IllegalStateException("audit unavailable")).doAnswer(call -> call.getArgument(0))
                .when(auditEvents).insert(any(AuditEvent.class));
        Certificate[] persisted = new Certificate[1];
        when(certificates.insert(any(Certificate.class))).thenAnswer(call -> {
            persisted[0] = call.getArgument(0);
            return persisted[0];
        });
        when(certificates.findByAttemptIdAndOrganizationId("attempt", "org-a"))
                .thenAnswer(call -> Optional.ofNullable(persisted[0]));

        assertThrows(IllegalStateException.class, () -> service().issue("teacher", "org-a", "attempt"));
        var repaired = service().issue("teacher", "org-a", "attempt");

        assertEquals(persisted[0].id(), repaired.id());
        verify(certificates).insert(any(Certificate.class));
        verify(entitlements, never()).refund(any());
    }
}
