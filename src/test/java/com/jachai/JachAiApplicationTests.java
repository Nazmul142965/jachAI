package com.jachai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(status().isOk());
    }

    @Test
    void resetRequestIsPublicButValidated() throws Exception {
        mvc.perform(post("/api/v1/auth/request-password-reset")
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

}
