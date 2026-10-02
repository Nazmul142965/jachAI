package com.jachai.billing.dto;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.UsageMetric;

public record PlanEntitlements(PlanTier tier, boolean academyEnabled, boolean talentEnabled,
                               boolean aiEnabled, int maxPublishedAssessments,
                               int monthlyInvitations, int dailyOrganizationAiRequests,
                               int dailyUserAiRequests, int monthlyCertificates,
                               int retentionDays) {
    public static PlanEntitlements forTier(PlanTier tier) {
        return switch (tier) {
            case FREE -> new PlanEntitlements(tier, true, true, true, 3, 30, 30, 5, 30, 90);
            case TEAM -> new PlanEntitlements(tier, true, true, true, 100, 2_000, 200, 25, 2_000, 90);
        };
    }

    public int limitFor(UsageMetric metric) {
        return switch (metric) {
            case PUBLISHED_ASSESSMENT -> maxPublishedAssessments;
            case INVITATION -> monthlyInvitations;
            case CERTIFICATE -> monthlyCertificates;
        };
    }
}
