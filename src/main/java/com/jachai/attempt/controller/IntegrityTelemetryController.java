package com.jachai.attempt.controller;

import com.jachai.attempt.dto.IntegrityReport;
import com.jachai.attempt.dto.RecordTelemetryRequest;
import com.jachai.attempt.dto.TelemetryEventView;
import com.jachai.attempt.service.IntegrityTelemetryService;
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
@RequestMapping("/api/v1/organizations/{organizationId}")
public class IntegrityTelemetryController {
    private final IntegrityTelemetryService telemetry;

    @PostMapping("/attempts/{attemptId}/telemetry")
    public ResponseEntity<TelemetryEventView> record(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId,
            @Valid @RequestBody RecordTelemetryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(telemetry.record(jwt.getSubject(), organizationId, attemptId, request));
    }

    @GetMapping("/talent/attempts/{attemptId}/integrity")
    public IntegrityReport report(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId) {
        return telemetry.report(jwt.getSubject(), organizationId, attemptId);
    }

    @GetMapping("/talent/attempts/{attemptId}/integrity/events")
    public Page<TelemetryEventView> events(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String attemptId,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return telemetry.events(jwt.getSubject(), organizationId, attemptId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "receivedAt")));
    }
}
