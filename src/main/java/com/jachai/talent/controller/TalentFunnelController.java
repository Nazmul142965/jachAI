package com.jachai.talent.controller;

import com.jachai.talent.service.TalentFunnelService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations/{organizationId}/talent/campaigns/{campaignId}/funnel")
public class TalentFunnelController {
    private final TalentFunnelService funnel;

    @GetMapping
    public TalentFunnelService.FunnelView get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @PathVariable String campaignId) {
        return funnel.funnel(jwt.getSubject(), organizationId, campaignId);
    }
}
