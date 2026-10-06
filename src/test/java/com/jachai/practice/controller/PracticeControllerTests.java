package com.jachai.practice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.practice.model.PracticeAnswer;
import com.jachai.practice.service.PracticeService;
import com.jachai.practice.service.PersonalPracticeGenerationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

class PracticeControllerTests {
    @Test
    void historyReturnsStablePageWithoutInternalUserOrDocumentIds() throws Exception {
        PracticeService service = org.mockito.Mockito.mock(PracticeService.class);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("learner")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        when(service.history("learner", 0)).thenReturn(new PageImpl<>(List.of(new PracticeAnswer(
                "learner:private-request", "learner", "java-record", "b", "b", true,
                "Explanation", Instant.now(), Instant.now().plusSeconds(3600)))));

        var response = new PracticeController(service,
                org.mockito.Mockito.mock(PersonalPracticeGenerationService.class)).history(jwt, 0);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertEquals(1, response.content().size());
        assertFalse(json.contains("learner:private-request"));
        assertFalse(json.contains("userId"));
        assertFalse(json.contains("expiresAt"));
    }
}
