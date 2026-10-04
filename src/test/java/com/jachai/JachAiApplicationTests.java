package com.jachai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.jachai.auth.service.AuthService;
import com.jachai.auth.service.GoogleAuthService;
import com.jachai.auth.service.EmailSender;
import com.jachai.organization.service.OrganizationService;
import com.jachai.organization.service.OrganizationAuditRecoveryService;
import com.jachai.organization.service.OrganizationSettingsService;
import com.jachai.organization.service.OrganizationInvitationService;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.service.QuestionService;
import com.jachai.assessment.service.AssessmentService;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.service.AssignmentService;
import com.jachai.attempt.service.AttemptService;
import com.jachai.attempt.service.AssignmentReminderService;
import com.jachai.attempt.repository.AttemptAnswerStore;
import com.jachai.attempt.service.IntegrityTelemetryService;
import com.jachai.organization.service.MembershipService;
import com.jachai.ai.service.QuestionDraftService;
import com.jachai.ai.service.TutorService;
import com.jachai.academy.service.CertificateService;
import com.jachai.academy.service.InstructorFeedbackService;
import com.jachai.academy.service.AcademyCatalogService;
import com.jachai.academy.service.BatchAssessmentService;
import com.jachai.academy.repository.BatchAnalyticsReader;
import com.jachai.messaging.service.AcademyMessagingService;
import com.jachai.academy.dto.CertificateVerificationView;
import com.jachai.talent.service.TalentCampaignService;
import com.jachai.talent.service.CampaignEmailInvitationService;
import com.jachai.billing.service.EntitlementService;
import com.jachai.billing.service.PaymentBillingService;
import com.jachai.billing.service.EntitlementUsageReconciliationService;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.platform.service.PlatformBillingSupportService;
import com.jachai.platform.service.PlatformOperationsService;
import com.jachai.platform.service.PlatformStorageService;
import com.jachai.platform.service.PlatformPlanService;
import com.jachai.platform.service.PlatformSupportAccessService;
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.AccessRevocationService;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import java.time.Instant;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JachAiApplicationTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;
    @MockitoBean GoogleAuthService googleAuthService;
    @MockitoBean EmailSender emailSender;
    @MockitoBean UserRepository userRepository;
    @MockitoBean AccessRevocationService accessRevocationService;
    @MockitoBean OrganizationService organizationService;
    @MockitoBean OrganizationSettingsService organizationSettingsService;
    @MockitoBean OrganizationAuditRecoveryService organizationAuditRecoveryService;
    @MockitoBean OrganizationInvitationService organizationInvitationService;
    @MockitoBean TenantAccessService tenantAccessService;
    @MockitoBean QuestionService questionService;
    @MockitoBean AssessmentService assessmentService;
    @MockitoBean PublishedVersionStore publishedVersionStore;
    @MockitoBean AssignmentService assignmentService;
    @MockitoBean AssignmentReminderService assignmentReminderService;
    @MockitoBean AttemptService attemptService;
    @MockitoBean AttemptAnswerStore attemptAnswerStore;
    @MockitoBean IntegrityTelemetryService integrityTelemetryService;
    @MockitoBean MembershipService membershipService;
    @MockitoBean QuestionDraftService questionDraftService;
    @MockitoBean TutorService tutorService;
    @MockitoBean CertificateService certificateService;
    @MockitoBean InstructorFeedbackService instructorFeedbackService;
    @MockitoBean AcademyCatalogService academyCatalogService;
    @MockitoBean BatchAssessmentService batchAssessmentService;
    @MockitoBean BatchAnalyticsReader batchAnalyticsReader;
    @MockitoBean AcademyMessagingService academyMessagingService;
    @MockitoBean TalentCampaignService talentCampaignService;
    @MockitoBean CampaignEmailInvitationService campaignEmailInvitationService;
    @MockitoBean EntitlementService entitlementService;
    @MockitoBean PaymentBillingService paymentBillingService;
    @MockitoBean EntitlementUsageReconciliationService entitlementUsageReconciliationService;
    @MockitoBean PlatformAdminService platformAdminService;
    @MockitoBean PlatformBillingSupportService platformBillingSupportService;
    @MockitoBean PlatformOperationsService platformOperationsService;
    @MockitoBean PlatformStorageService platformStorageService;
    @MockitoBean PlatformPlanService platformPlanService;
    @MockitoBean PlatformSupportAccessService platformSupportAccessService;

    @Test
    void healthIsPublicWithoutMongo() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void googleConfigurationIsPublicButAccountLinkRequiresAuthentication() throws Exception {
        when(googleAuthService.publicClientId()).thenReturn("");
        mvc.perform(get("/api/v1/auth/google/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").exists());
        mvc.perform(post("/api/v1/auth/google/link")
                        .contentType("application/json").content("{\"credential\":\"token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unimplementedApiIsClosed() throws Exception {
        mvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void platformMetadataApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/platform/operations/summary"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/operations/storage"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/organizations"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/organizations/org/billing"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/organizations/org/billing/orders"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/organizations/org/billing/usage-reconciliation"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/platform/organizations/org/billing/usage-reconciliation"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/organizations/org/billing/usage-reconciliation/audit"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/users/target"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/users/target/role"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/users/target/role/audit"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/platform/users/target/role")
                        .contentType("application/json").content("{\"platformAdmin\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void platformStorageEndpointUsesAuthenticatedActor() throws Exception {
        when(platformStorageService.usage("admin"))
                .thenReturn(new com.jachai.platform.dto.PlatformStorageView(
                        Instant.parse("2026-10-03T10:00:00Z"), 100L, 20L, 120L, 400L, false));
        mvc.perform(get("/api/v1/platform/operations/storage")
                        .with(jwt().jwt(token -> token.subject("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedLogicalBytes").value(120))
                .andExpect(jsonPath("$.warning").value(false));
        verify(platformStorageService).usage("admin");
    }

    @Test
    void platformRoleEndpointUsesAuthenticatedActorAndRejectsOrdinaryUser() throws Exception {
        doThrow(new com.jachai.shared.error.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "PLATFORM_ADMIN_REQUIRED", "Platform administrator access required"))
                .when(platformAdminService).changeRole("ordinary", "target", true);
        mvc.perform(put("/api/v1/platform/users/target/role")
                        .with(jwt().jwt(token -> token.subject("ordinary")))
                        .contentType("application/json").content("{\"platformAdmin\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));
        verify(platformAdminService).changeRole("ordinary", "target", true);
    }

    @Test
    void platformRoleEndpointValidatesBodyBeforeCallingService() throws Exception {
        mvc.perform(put("/api/v1/platform/users/target/role")
                        .with(jwt().jwt(token -> token.subject("admin")))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(platformAdminService, never()).changeRole(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void academyFeedbackApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/academy/attempts/attempt-a/feedback/mine"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void academyCatalogApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/academy/courses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void batchAssessmentProgressRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/academy/courses/course-a/batches/batch-a/assessments/ba-a/progress"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/organizations/org-a/academy/courses/course-a/batches/batch-a/assessments/ba-a/analytics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void academyMessagingRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/messaging/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void integrityTelemetryRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/organizations/org-a/attempts/attempt-a/telemetry")
                        .contentType("application/json").content("{\"type\":\"TAB_HIDDEN\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/organizations/org-a/talent/attempts/attempt-a/integrity"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emailInvitationAcceptanceRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/talent/email-invitations/accept")
                        .contentType("application/json").content("{\"token\":\"opaque\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workspaceInvitationRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/invitations"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/organizations/org-a/invitations")
                        .contentType("application/json")
                        .content("{\"email\":\"person@example.com\",\"role\":\"LEARNER\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/organizations/org-a/invitations/invite-a"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/organization-invitations/accept")
                        .contentType("application/json").content("{\"token\":\"opaque\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void billingEntitlementsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/billing/entitlements"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void certificateAuditRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/certificates/cert-a/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingReviewRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/attempts/review/pending?page=0"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/organizations/org-a/attempts/attempt-a/review"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staffReviewRouteUsesAuthenticatedActor() throws Exception {
        doThrow(new com.jachai.shared.error.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Staff only"))
                .when(attemptService).staffReview("candidate", "org-a", "attempt-a");

        mvc.perform(get("/api/v1/organizations/org-a/attempts/attempt-a/review")
                        .with(jwt().jwt(token -> token.subject("candidate"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(attemptService).staffReview("candidate", "org-a", "attempt-a");
    }

    @Test
    void organizationSettingsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bkashCallbackDoesNotCreditOnFailedBrowserStatus() throws Exception {
        mvc.perform(get("/api/v1/billing/bkash/callback")
                        .param("orderId", "J123456789012345678901234")
                        .param("paymentID", "payment12345")
                        .param("status", "failure"))
                .andExpect(status().isSeeOther());
        verify(paymentBillingService, never()).verifyBkashCallback(anyString(), anyString());
    }

    @Test
    void resetPageIsPublic() throws Exception {
        mvc.perform(get("/reset-password.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reset your password")));
    }

    @Test
    void dashboardTemplateAndStaticAssetsArePublic() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Technical assessment workspace")))
                .andExpect(content().string(containsString("Hiring campaigns")))
                .andExpect(content().string(containsString("campaign-candidates-pagination")))
                .andExpect(content().string(containsString("invite-candidate-pagination")))
                .andExpect(content().string(containsString("batch-progress-pagination")))
                .andExpect(content().string(containsString("messaging-partner-pagination")))
                .andExpect(content().string(containsString("conversations-pagination")))
                .andExpect(content().string(containsString("conversation-messages-pagination")));
        mvc.perform(get("/"))
                .andExpect(content().string(containsString("courses-pagination")))
                .andExpect(content().string(containsString("batch-assessments-pagination")))
                .andExpect(content().string(containsString("payment-history-pagination")));
        mvc.perform(get("/"))
                .andExpect(content().string(containsString("attempt-review-detail")))
                .andExpect(content().string(containsString("inspect-attempt")))
                .andExpect(content().string(containsString("platform-view")))
                .andExpect(content().string(containsString("platform-repair-usage")))
                .andExpect(content().string(containsString("platform-usage-audit-pagination")))
                .andExpect(content().string(containsString("platform-grant-role")))
                .andExpect(content().string(containsString("platform-role-audit-pagination")));
        mvc.perform(get("/verify-email.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Verify email")));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/app.css")).andExpect(status().isOk());
        mvc.perform(get("/theme.css")).andExpect(status().isOk());
        mvc.perform(get("/theme.js")).andExpect(status().isOk());
    }

    @Test
    void talentCampaignApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/talent/campaigns"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/organizations/org-a/talent/campaigns/campaign-a/candidates/candidate-a/review/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void membershipGovernanceApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/members/user-a/audit"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/organizations/org-a/members/user-a/role")
                        .contentType("application/json").content("{\"role\":\"INSTRUCTOR\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/organizations/org-a/members/user-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownershipTransferApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations/org-a/ownership/audit"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/organizations/org-a/owner")
                        .contentType("application/json").content("{\"targetUserId\":\"admin\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetRequestIsPublicButValidated() throws Exception {
        mvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void certificateVerificationIsPublicAndContainsOnlyVerificationData() throws Exception {
        when(certificateService.verify("public-code")).thenReturn(new CertificateVerificationView(
                "public-code", true, "Example Academy", "Java Basics", Instant.now(), null));

        mvc.perform(get("/api/v1/public/certificates/public-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.assessmentTitle").value("Java Basics"))
                .andExpect(jsonPath("$.learnerUserId").doesNotExist());
    }

    @Test
    void certificateVerificationPageIsPublic() throws Exception {
        mvc.perform(get("/certificates/public-code"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Certificate verification")));
    }

}
