package com.jachai.talent.controller;

import com.jachai.attempt.dto.CreateAssignmentRequest;
import com.jachai.talent.dto.CampaignInviteView;
import com.jachai.talent.dto.CampaignView;
import com.jachai.talent.dto.CandidateComparison;
import com.jachai.talent.dto.CreateCampaignRequest;
import com.jachai.talent.dto.ReviewRequest;
import com.jachai.talent.dto.ReviewAuditView;
import com.jachai.talent.service.TalentCampaignService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/talent/campaigns")
public class TalentCampaignController {
    private final TalentCampaignService campaigns;
    private final RateLimitService limits;

    @PostMapping
    public ResponseEntity<CampaignView> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaigns.create(jwt.getSubject(), organizationId, request));
    }

    @GetMapping
    public Page<CampaignView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                                    @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return campaigns.list(jwt.getSubject(), organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/{campaignId}/close")
    public CampaignView close(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                              @PathVariable String campaignId) {
        return campaigns.close(jwt.getSubject(), organizationId, campaignId);
    }

    @PostMapping("/{campaignId}/invitations")
    public ResponseEntity<CampaignInviteView> invite(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId,
            @Valid @RequestBody CreateAssignmentRequest request) {
        limits.consume(RateLimitPolicy.INVITE_ACTOR, organizationId + ":" + jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaigns.invite(jwt.getSubject(), organizationId, campaignId, request));
    }

    @GetMapping("/{campaignId}/candidates")
    public Page<CandidateComparison> compare(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return campaigns.compare(jwt.getSubject(), organizationId, campaignId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PutMapping("/{campaignId}/candidates/{candidateId}/review")
    public CandidateComparison review(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId,
            @PathVariable String candidateId, @Valid @RequestBody ReviewRequest request) {
        return campaigns.review(jwt.getSubject(), organizationId, campaignId, candidateId, request);
    }

    @GetMapping("/{campaignId}/candidates/{candidateId}/review/audit")
    public Page<ReviewAuditView> reviewAudit(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId,
            @PathVariable String candidateId, @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        return campaigns.audit(jwt.getSubject(), organizationId, campaignId, candidateId, page);
    }
}
