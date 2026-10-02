package com.jachai.academy.controller;

import com.jachai.academy.dto.BatchView;
import com.jachai.academy.dto.CourseView;
import com.jachai.academy.dto.CreateBatchRequest;
import com.jachai.academy.dto.CreateCourseRequest;
import com.jachai.academy.dto.EnrollLearnerRequest;
import com.jachai.academy.dto.EnrollmentView;
import com.jachai.academy.service.AcademyCatalogService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/organizations/{organizationId}/academy")
public class AcademyCatalogController {
    private final AcademyCatalogService catalog;

    @PostMapping("/courses")
    public ResponseEntity<CourseView> createCourse(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody CreateCourseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalog.createCourse(jwt.getSubject(), organizationId, request));
    }

    @GetMapping("/courses")
    public Page<CourseView> listCourses(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return catalog.listCourses(jwt.getSubject(), organizationId, page(page));
    }

    @PostMapping("/courses/{courseId}/batches")
    public ResponseEntity<BatchView> createBatch(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId,
            @Valid @RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalog.createBatch(jwt.getSubject(), organizationId, courseId, request));
    }

    @GetMapping("/courses/{courseId}/batches")
    public Page<BatchView> listBatches(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return catalog.listBatches(jwt.getSubject(), organizationId, courseId, page(page));
    }

    @PostMapping("/courses/{courseId}/batches/{batchId}/learners")
    public ResponseEntity<EnrollmentView> enroll(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId,
            @PathVariable String batchId, @Valid @RequestBody EnrollLearnerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.enroll(
                jwt.getSubject(), organizationId, courseId, batchId, request.learnerUserId()));
    }

    @GetMapping("/courses/{courseId}/batches/{batchId}/learners")
    public Page<EnrollmentView> listBatchLearners(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String courseId,
            @PathVariable String batchId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return catalog.listBatchLearners(jwt.getSubject(), organizationId, courseId, batchId,
                enrollmentPage(page));
    }

    @GetMapping("/batches/mine")
    public Page<EnrollmentView> myBatches(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return catalog.myBatches(jwt.getSubject(), organizationId, enrollmentPage(page));
    }

    private PageRequest page(int page) {
        return PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private PageRequest enrollmentPage(int page) {
        return PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "enrolledAt"));
    }
}
