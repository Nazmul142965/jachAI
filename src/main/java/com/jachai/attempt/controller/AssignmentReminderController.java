package com.jachai.attempt.controller;

import com.jachai.attempt.service.AssignmentReminderService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/assignments/{assignmentId}/reminders")
public class AssignmentReminderController {
    private final AssignmentReminderService reminders;
    private final RateLimitService limits;

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> queue(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String assignmentId) {
        limits.consume(RateLimitPolicy.INVITE_ACTOR, organizationId + ":" + jwt.getSubject());
        boolean queued = reminders.queue(jwt.getSubject(), organizationId, assignmentId);
        return ResponseEntity.status(queued ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(Map.of("queued", queued));
    }
}
