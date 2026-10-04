package com.jachai.organization.controller;

import com.jachai.organization.dto.CreateOrganizationRequest;
import com.jachai.organization.dto.OrganizationResponse;
import com.jachai.organization.dto.TransferOwnershipRequest;
import com.jachai.organization.dto.OwnershipAuditView;
import com.jachai.organization.service.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    public List<OrganizationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return organizations.listMine(jwt.getSubject());
    }

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizations.create(jwt.getSubject(), request));
    }

    @PutMapping("/{organizationId}/owner")
    public ResponseEntity<Void> transferOwner(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody TransferOwnershipRequest request) {
        organizations.transferOwner(jwt.getSubject(), organizationId, request.targetUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{organizationId}/ownership/audit")
    public Page<OwnershipAuditView> ownershipAudit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return organizations.ownershipAudit(jwt.getSubject(), organizationId, page);
    }
}
