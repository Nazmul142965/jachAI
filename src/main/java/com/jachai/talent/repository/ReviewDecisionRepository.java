package com.jachai.talent.repository;

import com.jachai.talent.model.ReviewDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReviewDecisionRepository extends MongoRepository<ReviewDecision, String> {
    Optional<ReviewDecision> findByIdAndOrganizationId(String id, String organizationId);
    List<ReviewDecision> findByOrganizationIdAndCampaignIdAndCandidateUserIdIn(
            String organizationId, String campaignId, List<String> candidateUserIds);
}
