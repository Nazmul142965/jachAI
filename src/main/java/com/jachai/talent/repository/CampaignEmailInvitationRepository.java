package com.jachai.talent.repository;

import com.jachai.talent.model.CampaignEmailInvitation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CampaignEmailInvitationRepository extends MongoRepository<CampaignEmailInvitation, String> {
    Optional<CampaignEmailInvitation> findByTokenHash(String tokenHash);
    Optional<CampaignEmailInvitation> findByOrganizationIdAndCampaignIdAndEmail(
            String organizationId, String campaignId, String email);

    Page<CampaignEmailInvitation> findByOrganizationIdAndCampaignId(
            String organizationId, String campaignId, Pageable pageable);
}
