package com.jachai.academy.controller;

import com.jachai.academy.dto.FeedbackView;
import com.jachai.academy.dto.WriteFeedbackRequest;
import com.jachai.academy.service.InstructorFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/academy/attempts/{attemptId}/feedback")
public class InstructorFeedbackController {
    private final InstructorFeedbackService feedback;

    @PutMapping
    public FeedbackView writeDraft(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                   @PathVariable String attemptId, @Valid @RequestBody WriteFeedbackRequest request) {
        return feedback.writeDraft(jwt.getSubject(), organizationId, attemptId, request);
    }

    @PostMapping("/publish")
    public FeedbackView publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                @PathVariable String attemptId) {
        return feedback.publish(jwt.getSubject(), organizationId, attemptId);
    }

    @GetMapping
    public FeedbackView staffView(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                  @PathVariable String attemptId) {
        return feedback.staffView(jwt.getSubject(), organizationId, attemptId);
    }

    @GetMapping("/mine")
    public FeedbackView learnerView(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                    @PathVariable String attemptId) {
        return feedback.learnerView(jwt.getSubject(), organizationId, attemptId);
    }
}
