package com.jachai.attempt.controller;

import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.AttemptSummary;
import com.jachai.attempt.dto.ResultView;
import com.jachai.attempt.dto.ReviewAttemptView;
import com.jachai.attempt.dto.StaffAttemptReviewView;
import com.jachai.attempt.dto.SaveAnswerRequest;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.service.AttemptService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.RequestParam;
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
@RequestMapping("/api/v1/organizations/{organizationId}")
public class AttemptController {
    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) { this.attempts = attempts; }

    @GetMapping("/assignments/mine")
    public List<Assignment> mine(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId) {
        return attempts.myAssignments(jwt.getSubject(), organizationId);
    }

    @GetMapping("/assignments/{assignmentId}/attempts/mine")
    public List<AttemptSummary> myAttempts(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String assignmentId) {
        return attempts.myAttempts(jwt.getSubject(), organizationId, assignmentId);
    }

    @PostMapping("/assignments/{assignmentId}/attempts")
    public ResponseEntity<AttemptView> start(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String assignmentId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attempts.start(jwt.getSubject(), organizationId, assignmentId));
    }

    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    public ResponseEntity<Void> saveAnswer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId,
            @PathVariable String questionId, @Valid @RequestBody SaveAnswerRequest request) {
        attempts.saveAnswer(jwt.getSubject(), organizationId, attemptId, questionId, request.selectedOptionIds());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public ResultView submit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return attempts.submit(jwt.getSubject(), organizationId, attemptId);
    }

    @GetMapping("/attempts/{attemptId}/result")
    public ResultView result(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return attempts.result(jwt.getSubject(), organizationId, attemptId);
    }

    @PostMapping("/attempts/{attemptId}/release")
    public ResultView release(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return attempts.releaseResult(jwt.getSubject(), organizationId, attemptId);
    }

    @GetMapping("/attempts/review/pending")
    public Page<ReviewAttemptView> pendingReview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") int page) {
        return attempts.pendingReview(jwt.getSubject(), organizationId, page);
    }

    @GetMapping("/attempts/{attemptId}/review")
    public StaffAttemptReviewView staffReview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return attempts.staffReview(jwt.getSubject(), organizationId, attemptId);
    }
}
