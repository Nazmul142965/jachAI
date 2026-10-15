package com.jachai.talent.service;

import com.jachai.attempt.model.Assignment;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import com.jachai.talent.model.CampaignEmailInvitation;
import com.jachai.talent.repository.HiringCampaignRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TalentFunnelService {
    private final TenantAccessService access;
    private final OrganizationRepository organizations;
    private final HiringCampaignRepository campaigns;
    private final MongoTemplate mongo;

    public record FunnelView(String campaignId, long retainedEmailInvitations,
                             long retainedAcceptedEmailInvitations, long assignedCandidates,
                             long submittedCandidates, long gradedCandidates,
                             long shortlistedCandidates, long hiredCandidates, long rejectedCandidates) { }

    public FunnelView funnel(String actorId, String organizationId, String campaignId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN, OrganizationRole.RECRUITER);
        var organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));
        if (organization.type() != OrganizationType.TALENT) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TALENT_ONLY", "A Talent workspace is required");
        }
        campaigns.findByIdAndOrganizationId(campaignId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campaign not found"));
        Criteria scope = Criteria.where("organizationId").is(organizationId).and("campaignId").is(campaignId);
        long retainedInvitations = mongo.count(Query.query(scope), CampaignEmailInvitation.class);
        long retainedAccepted = mongo.count(Query.query(Criteria.where("organizationId").is(organizationId)
                .and("campaignId").is(campaignId).and("acceptedAt").ne(null)), CampaignEmailInvitation.class);

        AggregationOperation match = context -> new Document("$match", new Document("organizationId", organizationId)
                .append("campaignId", campaignId));
        AggregationOperation latestAttempt = context -> new Document("$lookup", new Document("from", "attempts")
                .append("let", new Document("assignmentId", "$_id").append("organizationId", "$organizationId"))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$and", List.of(
                                new Document("$eq", List.of("$assignmentId", "$$assignmentId")),
                                new Document("$eq", List.of("$organizationId", "$$organizationId")),
                                new Document("$eq", List.of("$status", "SUBMITTED")))))),
                        new Document("$sort", new Document("attemptNumber", -1)),
                        new Document("$limit", 1),
                        new Document("$project", new Document("percentage", 1))))
                .append("as", "latestAttempt"));
        AggregationOperation unwindAttempt = context -> new Document("$unwind",
                new Document("path", "$latestAttempt").append("preserveNullAndEmptyArrays", true));
        AggregationOperation currentDecision = context -> new Document("$lookup",
                new Document("from", "talent_review_decisions")
                        .append("let", new Document("candidateId", "$assignedUserId")
                                .append("attemptId", "$latestAttempt._id"))
                        .append("pipeline", List.of(new Document("$match", new Document("$expr",
                                new Document("$and", List.of(
                                        new Document("$eq", List.of("$organizationId", organizationId)),
                                        new Document("$eq", List.of("$campaignId", campaignId)),
                                        new Document("$eq", List.of("$candidateUserId", "$$candidateId")),
                                        new Document("$eq", List.of("$attemptId", "$$attemptId"))))))))
                        .append("as", "currentDecision"));
        AggregationOperation unwindDecision = context -> new Document("$unwind",
                new Document("path", "$currentDecision").append("preserveNullAndEmptyArrays", true));
        AggregationOperation group = context -> new Document("$group", new Document("_id", null)
                .append("assigned", new Document("$sum", 1))
                .append("submitted", sumWhen(new Document("$ifNull", List.of("$latestAttempt._id", false))))
                .append("graded", sumWhen(new Document("$gte", List.of(
                        new Document("$ifNull", List.of("$latestAttempt.percentage", -1)), 0))))
                .append("shortlisted", decisionCount("SHORTLISTED"))
                .append("hired", decisionCount("HIRED"))
                .append("rejected", decisionCount("REJECTED")));
        Document totals = mongo.aggregate(Aggregation.newAggregation(match, latestAttempt, unwindAttempt,
                currentDecision, unwindDecision, group), "assignments", Document.class).getUniqueMappedResult();
        return new FunnelView(campaignId, retainedInvitations, retainedAccepted,
                number(totals, "assigned"), number(totals, "submitted"), number(totals, "graded"),
                number(totals, "shortlisted"), number(totals, "hired"), number(totals, "rejected"));
    }

    private static Document decisionCount(String decision) {
        return sumWhen(new Document("$eq", List.of("$currentDecision.decision", decision)));
    }

    private static Document sumWhen(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }

    private static long number(Document source, String key) {
        return source != null && source.get(key) instanceof Number value ? value.longValue() : 0;
    }
}
