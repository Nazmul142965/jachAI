package com.jachai.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jachai.ai.service.*;
import com.jachai.resume.model.ResumeReview;
import com.jachai.shared.error.ApiException;
import com.jachai.shared.security.RateLimitService;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeReviewServiceTests {
    private final ResumeScanService scans = mock(ResumeScanService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AiService ai = mock(AiService.class);
    private final RateLimitService limits = mock(RateLimitService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ResumeReviewService service = new ResumeReviewService(scans, mongo, ai, json, limits);
    private final ResumeScanService.ReviewInput input = new ResumeScanService.ReviewInput(
            "Experience\nWorked on backend development.\nProjects\nBuilt a library booking service.",
            "Required: experience with Java and SQL.", Instant.now().plusSeconds(86400));

    private String response(String source, String evidence) throws Exception {
        return json.writeValueAsString(Map.of("summary", "The description of your backend work could be more specific.",
                "findings", List.of(Map.of("source", source, "severity", "MEDIUM", "title", "Describe your contribution",
                        "evidence", evidence, "explanation", "This passage does not explain what you personally delivered.",
                        "action", "Name the service you changed and describe your contribution without inventing metrics.")), "caveats", List.of()));
    }

    @Test void missingConsentBlocksAllDataReadsAndProviderCalls() {
        assertThatThrownBy(() -> service.review("owner", "scan", false)).isInstanceOf(ApiException.class).hasMessageContaining("permission");
        verifyNoInteractions(scans, mongo, ai, limits);
    }

    @Test void ownerAuthorizationRunsBeforeReviewAccess() {
        when(scans.reviewInput("other", "scan")).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Not found"));
        assertThatThrownBy(() -> service.get("other", "scan")).isInstanceOf(ApiException.class);
        verifyNoInteractions(mongo, ai);
    }

    @Test void acceptsOnlyEvidencePresentInTheChosenSource() throws Exception {
        var review = service.validate(response("RESUME", "Worked on backend development."), input);
        assertThat(review.findings()).hasSize(1);
        assertThat(review.findings().getFirst().evidence()).isEqualTo("Worked on backend development.");
        assertThat(review.caveats()).anyMatch(c -> c.contains("may still be mistaken"));
        assertThatThrownBy(() -> service.validate(response("RESUME", "Managed a team of twenty engineers."), input))
                .isInstanceOf(ApiException.class).hasMessageContaining("could not be verified");
        assertThatThrownBy(() -> service.validate(response("JOB", "Worked on backend development."), input))
                .isInstanceOf(ApiException.class);
    }

    @Test void doesNotForceTheSameFindingOntoDifferentResumeText() throws Exception {
        String output = response("RESUME", "Worked on backend development.");
        var different = new ResumeScanService.ReviewInput("Built and tested a library booking service using Java and SQL.", "", input.expiresAt());
        assertThatThrownBy(() -> service.validate(output, different)).isInstanceOf(ApiException.class);
    }

    @Test void usesActualResumeAndJobAndStoresOnlyTheValidatedReview() throws Exception {
        when(scans.reviewInput("owner", "scan")).thenReturn(input);
        when(ai.generate(eq(AiUseCase.RESUME_REVIEW), anyString(), anyString()))
                .thenReturn(response("RESUME", "Worked on backend development."));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(ResumeReview.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
        assertThat(service.review("owner", "scan", true).status()).isEqualTo("COMPLETED");
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(ai).generate(eq(AiUseCase.RESUME_REVIEW), anyString(), prompt.capture());
        assertThat(json.readTree(prompt.getValue()).get("resume").asText()).isEqualTo(input.text());
        assertThat(json.readTree(prompt.getValue()).get("jobDescription").asText()).isEqualTo(input.jobDescription());
        verify(mongo, never()).upsert(any(Query.class), any(Update.class), eq(ResumeReview.class));
    }

    @Test void unavailableProviderFailsHonestlyInsteadOfReturningCannedAdvice() {
        when(scans.reviewInput("owner", "scan")).thenReturn(input);
        when(ai.generate(any(), anyString(), anyString())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "AI is not turned on"));
        assertThatThrownBy(() -> service.review("owner", "scan", true)).isInstanceOf(ApiException.class).hasMessageContaining("not turned on");
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq(ResumeReview.class));
        assertThat(update.getValue().getUpdateObject().get("$set", org.bson.Document.class)).containsEntry("status", "FAILED");
    }

    @Test void malformedOrDuplicateFindingsAreNotSaved() throws Exception {
        assertThatThrownBy(() -> service.validate("not JSON", input)).isInstanceOf(Exception.class);
        var output = json.readTree(response("RESUME", "Worked on backend development."));
        ((com.fasterxml.jackson.databind.node.ArrayNode) output.get("findings")).add(output.get("findings").get(0));
        assertThatThrownBy(() -> service.validate(output.toString(), input)).isInstanceOf(ApiException.class);
    }
}
