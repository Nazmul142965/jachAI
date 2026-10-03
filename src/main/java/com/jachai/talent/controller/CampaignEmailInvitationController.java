package com.jachai.talent.controller;

import com.jachai.attempt.model.Assignment;
import com.jachai.talent.dto.AcceptEmailInvitationRequest;
import com.jachai.talent.dto.CreateEmailInvitationRequest;
import com.jachai.talent.dto.EmailInvitationView;
import com.jachai.talent.service.CampaignEmailInvitationService;
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
@RequestMapping("/api/v1")
public class CampaignEmailInvitationController {
    private final CampaignEmailInvitationService invitations;
    private final RateLimitService limits;

    @GetMapping("/organizations/{organizationId}/talent/campaigns/{campaignId}/email-invitations")
    public Page<EmailInvitationView> list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return invitations.list(jwt.getSubject(), organizationId, campaignId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
    }

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
