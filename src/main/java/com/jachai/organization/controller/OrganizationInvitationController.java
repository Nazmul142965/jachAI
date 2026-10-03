package com.jachai.organization.controller;

import com.jachai.organization.dto.CreateOrganizationInvitationRequest;
import com.jachai.organization.dto.OrganizationInvitationView;
import com.jachai.organization.service.OrganizationInvitationService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class OrganizationInvitationController {
    private final OrganizationInvitationService invitations;
    private final RateLimitService limits;

    public record AcceptRequest(@NotBlank String token) { }

    @PostMapping("/organizations/{organizationId}/invitations")
    public ResponseEntity<OrganizationInvitationView> invite(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId,
            @Valid @RequestBody CreateOrganizationInvitationRequest request) {
        limits.consume(RateLimitPolicy.INVITE_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitations.invite(jwt.getSubject(), organizationId, request));
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    public Page<OrganizationInvitationView> list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return invitations.list(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    @DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
    public OrganizationInvitationView revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String invitationId) {
        return invitations.revoke(jwt.getSubject(), organizationId, invitationId);
    }

    @PostMapping("/organization-invitations/accept")
    public OrganizationInvitationView accept(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AcceptRequest request) {
        return invitations.accept(jwt.getSubject(), request.token());
    }
}
