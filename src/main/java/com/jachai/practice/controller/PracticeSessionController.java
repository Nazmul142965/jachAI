package com.jachai.practice.controller;

import com.jachai.practice.service.PracticeCatalog;
import com.jachai.practice.service.PracticeService;
import com.jachai.practice.service.PracticeSessionService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/practice")
public class PracticeSessionController {
    private final PracticeService practice;
    private final PracticeCatalog catalog;
    private final PracticeSessionService sessions;
    private final RateLimitService limits;

    public record StartRequest(@NotBlank String pathId, @Min(1) @Max(4) int questionCount) { }
    public record SessionAnswerRequest(@NotBlank String questionId, @NotBlank String optionId) { }

    @GetMapping("/paths")
    public List<PracticeCatalog.PathView> paths(@AuthenticationPrincipal Jwt jwt) {
        practice.usage(jwt.getSubject());
        return catalog.paths();
    }

    @PostMapping("/sessions")
    public ResponseEntity<PracticeSessionService.SessionView> start(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody StartRequest request) {
        limits.consume(RateLimitPolicy.PRACTICE_SESSION_ACTOR, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessions.start(jwt.getSubject(), request.pathId(), request.questionCount()));
    }

    @GetMapping("/sessions/{sessionId}")
    public PracticeSessionService.SessionView get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId) {
        return sessions.get(jwt.getSubject(), sessionId);
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public PracticeSessionService.SessionView answer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId, @Valid @RequestBody SessionAnswerRequest request) {
        return sessions.answer(jwt.getSubject(), sessionId, request.questionId(), request.optionId());
    }

    @GetMapping("/next")
    public PracticeSessionService.NextPracticeView next(@AuthenticationPrincipal Jwt jwt) {
        return sessions.next(jwt.getSubject());
    }
}
