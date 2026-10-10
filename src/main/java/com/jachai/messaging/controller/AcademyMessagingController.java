package com.jachai.messaging.controller;

import com.jachai.messaging.dto.ChatMessageView;
import com.jachai.messaging.dto.ConversationView;
import com.jachai.messaging.dto.CreateConversationRequest;
import com.jachai.messaging.dto.MessagingPartnerView;
import com.jachai.messaging.dto.SendMessageRequest;
import com.jachai.messaging.service.AcademyMessagingService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/messaging")
public class AcademyMessagingController {
    private final AcademyMessagingService messaging;
    private final RateLimitService limits;

    @GetMapping("/partners")
    public Page<MessagingPartnerView> partners(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return messaging.partners(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationView> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody CreateConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messaging.create(jwt.getSubject(), organizationId, request.partnerUserId()));
    }

    @GetMapping("/conversations")
    public Page<ConversationView> conversations(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        // Most recently active first, as in any chat. Conversations nobody has written in yet have no
        // lastMessageAt and fall to the end, newest first.
        return messaging.list(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "lastMessageAt")
                        .and(Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/unread")
    public java.util.Map<String, Integer> unread(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId) {
        return java.util.Map.of("unread", messaging.unreadTotal(jwt.getSubject(), organizationId));
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String conversationId) {
        messaging.markRead(jwt.getSubject(), organizationId, conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Page<ChatMessageView> messages(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return messaging.messages(jwt.getSubject(), organizationId, conversationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "sentAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ChatMessageView> send(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        limits.consume(RateLimitPolicy.MESSAGE_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(messaging.send(
                jwt.getSubject(), organizationId, conversationId, request.body()));
    }
}
