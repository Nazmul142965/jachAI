package com.jachai.organization.controller;

import com.jachai.organization.dto.CreateOrganizationRequest;
import com.jachai.organization.dto.OrganizationResponse;
import com.jachai.organization.service.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
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
}
