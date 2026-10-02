package com.jachai.ai.controller;

import com.jachai.ai.dto.DraftQuestionRequest;
import com.jachai.ai.dto.TutorReply;
import com.jachai.ai.dto.TutorRequest;
import com.jachai.ai.service.QuestionDraftService;
import com.jachai.ai.service.TutorService;
import com.jachai.question.model.Question;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}")
public class AiController {
    private final QuestionDraftService drafts;
    private final TutorService tutor;
    private final RateLimitService limits;

    @PostMapping("/ai/question-drafts")
    public ResponseEntity<Question> draft(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody DraftQuestionRequest request) {
        limits.consume(RateLimitPolicy.AI_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(drafts.draft(jwt.getSubject(), organizationId, request));
    }

    @PostMapping("/attempts/{attemptId}/tutor")
    public TutorReply explain(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                              @PathVariable String attemptId, @Valid @RequestBody TutorRequest request) {
        limits.consume(RateLimitPolicy.AI_ACTOR, organizationId + ":" + jwt.getSubject());
        return tutor.explain(jwt.getSubject(), organizationId, attemptId, request);
    }
}
