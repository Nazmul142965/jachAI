package com.jachai.academy.controller;

import com.jachai.academy.dto.BatchAssessmentView;
import com.jachai.academy.dto.BatchAssessmentAnalyticsView;
import com.jachai.academy.dto.BatchDistributionResult;
import com.jachai.academy.dto.BatchProgressRow;
import com.jachai.academy.dto.CreateBatchAssessmentRequest;
import com.jachai.academy.service.BatchAssessmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/academy/courses/{courseId}/batches/{batchId}/assessments")
public class BatchAssessmentController {
    private final BatchAssessmentService batchAssessments;

    @PostMapping
    public ResponseEntity<BatchAssessmentView> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId, @PathVariable String batchId,
            @Valid @RequestBody CreateBatchAssessmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(batchAssessments.create(
                jwt.getSubject(), organizationId, courseId, batchId, request));
    }

    @GetMapping
    public Page<BatchAssessmentView> list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId, @PathVariable String batchId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return batchAssessments.list(jwt.getSubject(), organizationId, courseId, batchId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/{batchAssessmentId}/reconcile")
    public BatchDistributionResult reconcile(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId, @PathVariable String batchId,
            @PathVariable String batchAssessmentId) {
        return batchAssessments.reconcile(jwt.getSubject(), organizationId, courseId, batchId,
                batchAssessmentId);
    }

    @GetMapping("/{batchAssessmentId}/progress")
    public Page<BatchProgressRow> progress(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId, @PathVariable String batchId,
            @PathVariable String batchAssessmentId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return batchAssessments.progress(jwt.getSubject(), organizationId, courseId, batchId,
                batchAssessmentId, PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/{batchAssessmentId}/analytics")
    public BatchAssessmentAnalyticsView analytics(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId,
            @PathVariable String batchId, @PathVariable String batchAssessmentId) {
        return batchAssessments.analytics(jwt.getSubject(), organizationId, courseId,
                batchId, batchAssessmentId);
    }
}
