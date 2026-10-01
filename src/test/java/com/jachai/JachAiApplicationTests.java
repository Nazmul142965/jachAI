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
import com.jachai.organization.service.OrganizationService;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.service.QuestionService;
import com.jachai.assessment.service.AssessmentService;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.service.AssignmentService;
import com.jachai.attempt.service.AttemptService;
import com.jachai.organization.service.MembershipService;
import com.jachai.ai.service.QuestionDraftService;
import com.jachai.ai.service.TutorService;
import com.jachai.academy.service.CertificateService;
import com.jachai.academy.dto.CertificateVerificationView;
import static org.mockito.Mockito.when;
import java.time.Instant;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JachAiApplicationTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;
    @MockitoBean OrganizationService organizationService;
    @MockitoBean TenantAccessService tenantAccessService;
    @MockitoBean QuestionService questionService;
    @MockitoBean AssessmentService assessmentService;
    @MockitoBean PublishedVersionStore publishedVersionStore;
    @MockitoBean AssignmentService assignmentService;
    @MockitoBean AttemptService attemptService;
    @MockitoBean MembershipService membershipService;
    @MockitoBean QuestionDraftService questionDraftService;
    @MockitoBean TutorService tutorService;
    @MockitoBean CertificateService certificateService;

    @Test
    void healthIsPublicWithoutMongo() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unimplementedApiIsClosed() throws Exception {
        mvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
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
                .andExpect(content().string(containsString("Technical assessment workspace")));
        mvc.perform(get("/verify-email.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("JachAI email verification")));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/app.css")).andExpect(status().isOk());
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

}
