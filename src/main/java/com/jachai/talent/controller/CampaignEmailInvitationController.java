package com.jachai.talent.controller;

import com.jachai.attempt.model.Assignment;
import com.jachai.talent.dto.AcceptEmailInvitationRequest;
import com.jachai.talent.dto.CreateEmailInvitationRequest;
import com.jachai.talent.dto.EmailInvitationView;
import com.jachai.talent.service.CampaignEmailInvitationService;
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
@RequestMapping("/api/v1")
public class CampaignEmailInvitationController {
    private final CampaignEmailInvitationService invitations;
    private final RateLimitService limits;

    @PostMapping("/organizations/{organizationId}/talent/campaigns/{campaignId}/email-invitations")
    public ResponseEntity<EmailInvitationView> invite(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId,
            @Valid @RequestBody CreateEmailInvitationRequest request) {
        limits.consume(RateLimitPolicy.INVITE_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitations.invite(jwt.getSubject(), organizationId, campaignId, request));
    }

    @PostMapping("/talent/email-invitations/accept")
    public Assignment accept(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AcceptEmailInvitationRequest request) {
        return invitations.accept(jwt.getSubject(), request.token());
    }
}
