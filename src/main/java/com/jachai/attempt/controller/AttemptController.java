package com.jachai.attempt.controller;

import com.jachai.attempt.dto.AttemptView;
import com.jachai.attempt.dto.AttemptSummary;
import com.jachai.attempt.dto.AssignmentView;
import com.jachai.attempt.dto.ResultView;
import com.jachai.attempt.dto.ReviewAttemptView;
import com.jachai.attempt.dto.StaffAttemptReviewView;
import com.jachai.attempt.dto.SaveAnswerRequest;
import com.jachai.attempt.dto.SaveTextAnswerRequest;
import com.jachai.attempt.dto.GradeTextAnswerRequest;
import com.jachai.attempt.dto.ManualGradeAuditView;
import com.jachai.attempt.service.AttemptService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RequestMapping("/api/v1/organizations/{organizationId}")
public class AttemptController {
    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) { this.attempts = attempts; }

    @GetMapping("/assignments/mine")
    public Page<AssignmentView> mine(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return attempts.myAssignments(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @GetMapping("/assignments/{assignmentId}")
    public AssignmentView myAssignment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String assignmentId) {
        return attempts.myAssignment(jwt.getSubject(), organizationId, assignmentId);
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

    @PutMapping("/attempts/{attemptId}/answers/{questionId}/text")
    public ResponseEntity<Void> saveTextAnswer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId,
            @PathVariable String questionId, @Valid @RequestBody SaveTextAnswerRequest request) {
        attempts.saveTextAnswer(jwt.getSubject(), organizationId, attemptId, questionId, request.text());
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
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return attempts.pendingReview(jwt.getSubject(), organizationId, page);
    }

    @GetMapping("/attempts/{attemptId}/review")
    public StaffAttemptReviewView staffReview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return attempts.staffReview(jwt.getSubject(), organizationId, attemptId);
    }

    @PutMapping("/attempts/{attemptId}/review/questions/{questionId}/grade")
    public StaffAttemptReviewView gradeTextAnswer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId,
            @PathVariable String questionId, @Valid @RequestBody GradeTextAnswerRequest request) {
        return attempts.gradeTextAnswer(jwt.getSubject(), organizationId, attemptId,
                questionId, request.correct());
    }

    @GetMapping("/attempts/{attemptId}/review/grades/audit")
    public Page<ManualGradeAuditView> manualGradeAudit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return attempts.manualGradeAudit(jwt.getSubject(), organizationId, attemptId, page);
    }
}
