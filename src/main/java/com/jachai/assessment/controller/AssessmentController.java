package com.jachai.assessment.controller;

import com.jachai.assessment.dto.CreateAssessmentRequest;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.AssessmentVersion;
import com.jachai.assessment.service.AssessmentService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/organizations/{organizationId}/assessments")
public class AssessmentController {
    private final AssessmentService assessments;

    public AssessmentController(AssessmentService assessments) { this.assessments = assessments; }

    @GetMapping
    public Page<Assessment> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) AssessmentStatus status) {
        return assessments.list(jwt.getSubject(), organizationId, status,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @PostMapping
    public ResponseEntity<Assessment> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody CreateAssessmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assessments.create(jwt.getSubject(), organizationId, request));
    }

    @PutMapping("/{assessmentId}/draft")
    public Assessment updateDraft(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
            @PathVariable String assessmentId, @RequestHeader("If-Match") long expectedRevision,
            @Valid @RequestBody CreateAssessmentRequest request) {
        return assessments.updateDraft(jwt.getSubject(), organizationId, assessmentId, expectedRevision, request);
    }

    @GetMapping("/{assessmentId}/versions")
    public Page<AssessmentVersion> versions(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
            @PathVariable String assessmentId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return assessments.listVersions(jwt.getSubject(), organizationId, assessmentId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "version")));
    }

    @PostMapping("/{assessmentId}/publish")
    public Map<String, Object> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                        @PathVariable String assessmentId) {
        var version = assessments.publish(jwt.getSubject(), organizationId, assessmentId);
        return Map.of("assessmentId", version.assessmentId(), "version", version.version(),
                "publishedAt", version.publishedAt());
    }

    @PostMapping("/{assessmentId}/versions")
    public Map<String, Object> reviseAndPublish(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String assessmentId,
            @RequestHeader("If-Match") long expectedRevision,
            @Valid @RequestBody CreateAssessmentRequest request) {
        var version = assessments.reviseAndPublish(jwt.getSubject(), organizationId, assessmentId,
                expectedRevision, request);
        return Map.of("assessmentId", version.assessmentId(), "version", version.version(),
                "publishedAt", version.publishedAt());
    }
}
