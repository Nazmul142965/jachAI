package com.jachai.organization.controller;

import com.jachai.organization.dto.AddMemberRequest;
import com.jachai.organization.dto.ChangeMemberRoleRequest;
import com.jachai.organization.dto.MemberResponse;
import com.jachai.organization.dto.MemberAuditView;
import com.jachai.organization.service.MembershipService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/organizations/{organizationId}/members")
@RequiredArgsConstructor
public class MembershipController {
    private final MembershipService members;

    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId) {
        return members.list(jwt.getSubject(), organizationId);
    }

    @GetMapping("/candidates")
    public Page<MemberResponse> candidates(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) int page) {
        return members.listCandidates(jwt.getSubject(), organizationId, PageRequest.of(page, 20));
    }

    @GetMapping("/candidates/lookup")
    public MemberResponse candidateByEmail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId,
            @RequestParam @NotBlank @Email String email) {
        return members.findCandidateByEmail(jwt.getSubject(), organizationId, email);
    }

    @PostMapping
    public ResponseEntity<MemberResponse> add(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(members.add(jwt.getSubject(), organizationId, request));
    }

    @PutMapping("/{userId}/role")
    public MemberResponse changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
            @PathVariable String userId, @Valid @RequestBody ChangeMemberRoleRequest request) {
        return members.changeRole(jwt.getSubject(), organizationId, userId, request.role());
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String userId) {
        members.deactivate(jwt.getSubject(), organizationId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/audit")
    public Page<MemberAuditView> audit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String userId,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return members.audit(jwt.getSubject(), organizationId, userId, page);
    }
}
