package com.jachai.talent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.organization.model.Organization;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.repository.HiringCampaignRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class TalentFunnelServiceTests {
    @Mock TenantAccessService access;
    @Mock OrganizationRepository organizations;
    @Mock HiringCampaignRepository campaigns;
    @Mock MongoTemplate mongo;

    @Test
    void funnelCountsOnlyThisCampaignAndMatchesReviewsToLatestAttempt() {
        when(organizations.findById("org")).thenReturn(Optional.of(
                new Organization("org", "Hiring", OrganizationType.TALENT, "owner", Instant.now())));
        when(campaigns.findByIdAndOrganizationId("campaign", "org")).thenReturn(Optional.of(
                new HiringCampaign("campaign", "org", "assessment", "version", "Role", "owner",
                        true, Instant.now(), null)));
        when(mongo.count(any(Query.class), eq(CampaignEmailInvitation.class))).thenReturn(5L, 2L);
        when(mongo.aggregate(any(Aggregation.class), eq("assignments"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(new Document("assigned", 4)
                        .append("submitted", 3).append("graded", 2).append("shortlisted", 1)
                        .append("hired", 1).append("rejected", 0)), new Document()));

        var result = new TalentFunnelService(access, organizations, campaigns, mongo)
                .funnel("recruiter", "org", "campaign");

        assertEquals(5, result.retainedEmailInvitations());
        assertEquals(4, result.assignedCandidates());
        assertEquals(1, result.hiredCandidates());
        ArgumentCaptor<Aggregation> pipeline = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(pipeline.capture(), eq("assignments"), eq(Document.class));
        String stages = pipeline.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
        assertTrue(stages.contains("attemptId"));
        assertTrue(stages.contains("HIRED"));
        verify(access).requireRole("recruiter", "org", OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
    }

    @Test
    void missingCampaignDoesNotReadOtherTenantData() {
        when(organizations.findById("org")).thenReturn(Optional.of(
                new Organization("org", "Hiring", OrganizationType.TALENT, "owner", Instant.now())));
        assertThrows(ApiException.class, () -> new TalentFunnelService(access, organizations, campaigns, mongo)
                .funnel("recruiter", "org", "missing"));
        verify(mongo, never()).aggregate(any(Aggregation.class), eq("assignments"), eq(Document.class));
    }
}
