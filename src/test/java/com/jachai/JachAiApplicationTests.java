package com.jachai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

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
import com.jachai.organization.service.OrganizationSettingsService;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.service.QuestionService;
import com.jachai.assessment.service.AssessmentService;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.service.AssignmentService;
import com.jachai.attempt.service.AttemptService;
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
import com.jachai.auth.repository.UserRepository;
import com.jachai.auth.service.AccessRevocationService;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @MockitoBean TenantAccessService tenantAccessService;
    @MockitoBean QuestionService questionService;
    @MockitoBean AssessmentService assessmentService;
    @MockitoBean PublishedVersionStore publishedVersionStore;
    @MockitoBean AssignmentService assignmentService;
    @MockitoBean AttemptService attemptService;
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
                .andExpect(content().string(containsString("batch-progress-pagination")));
        mvc.perform(get("/verify-email.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("JachAI email verification")));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/app.css")).andExpect(status().isOk());
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
