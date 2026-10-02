package com.jachai.attempt.controller;

import com.jachai.attempt.dto.CreateAssignmentRequest;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.service.AssignmentService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/assessments/{assessmentId}/assignments")
public class AssignmentController {
    private final AssignmentService assignments;
    private final RateLimitService limits;

    @PostMapping
    public ResponseEntity<Assignment> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String assessmentId,
            @Valid @RequestBody CreateAssignmentRequest request) {
        limits.consume(RateLimitPolicy.INVITE_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assignments.assign(jwt.getSubject(), organizationId, assessmentId, request));
    }
}
