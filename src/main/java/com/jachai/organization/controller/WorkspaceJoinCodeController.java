package com.jachai.organization.controller;

import com.jachai.organization.dto.CreatedWorkspaceJoinCode;
import com.jachai.organization.dto.OrganizationResponse;
import com.jachai.organization.dto.WorkspaceJoinCodeView;
import com.jachai.organization.service.WorkspaceJoinCodeService;
import com.jachai.shared.security.RateLimitPolicy;
import com.jachai.shared.security.RateLimitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class WorkspaceJoinCodeController {
    public record JoinRequest(@NotBlank @Size(max = 32) String code) { }

    private final WorkspaceJoinCodeService codes;
    private final RateLimitService limits;

    @PostMapping("/organizations/{organizationId}/join-codes")
    public ResponseEntity<CreatedWorkspaceJoinCode> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId) {
        limits.consume(RateLimitPolicy.INVITE_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(codes.create(jwt.getSubject(), organizationId));
    }

    @GetMapping("/organizations/{organizationId}/join-codes")
    public Page<WorkspaceJoinCodeView> list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return codes.list(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @DeleteMapping("/organizations/{organizationId}/join-codes/{codeId}")
    public WorkspaceJoinCodeView revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String codeId) {
        return codes.revoke(jwt.getSubject(), organizationId, codeId);
    }

    @PostMapping("/join-codes/accept")
    public OrganizationResponse join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JoinRequest request) {
        limits.consume(RateLimitPolicy.JOIN_CODE_ACCOUNT, jwt.getSubject());
        return codes.join(jwt.getSubject(), request.code());
    }
}
