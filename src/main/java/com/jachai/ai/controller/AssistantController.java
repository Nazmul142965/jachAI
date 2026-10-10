package com.jachai.ai.controller;

import com.jachai.ai.dto.AssistantReply;
import com.jachai.ai.dto.AssistantRequest;
import com.jachai.ai.service.AssistantService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The floating study helper. Personal: it belongs to the signed-in person, not to an organization. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final AssistantService assistant;
    private final RateLimitService limits;

    @GetMapping("/status")
    public AssistantService.Status status(@AuthenticationPrincipal Jwt jwt) {
        return assistant.status(jwt.getSubject());
    }

    @PostMapping("/chat")
    public AssistantReply chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AssistantRequest request) {
        limits.consume(RateLimitPolicy.AI_ACTOR, "assistant:" + jwt.getSubject());
        return assistant.chat(jwt.getSubject(), request);
    }
}
