package com.jachai.assessment.controller;

import com.jachai.assessment.dto.CreateAssessmentRequest;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.service.AssessmentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/assessments")
public class AssessmentController {
    private final AssessmentService assessments;

    public AssessmentController(AssessmentService assessments) { this.assessments = assessments; }

    @GetMapping
    public List<Assessment> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId) {
        return assessments.list(jwt.getSubject(), organizationId);
    }

    @PostMapping
    public ResponseEntity<Assessment> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody CreateAssessmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assessments.create(jwt.getSubject(), organizationId, request));
    }

    @PostMapping("/{assessmentId}/publish")
    public Map<String, Object> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                        @PathVariable String assessmentId) {
        var version = assessments.publish(jwt.getSubject(), organizationId, assessmentId);
        return Map.of("assessmentId", version.assessmentId(), "version", version.version(),
                "publishedAt", version.publishedAt());
    }
}
