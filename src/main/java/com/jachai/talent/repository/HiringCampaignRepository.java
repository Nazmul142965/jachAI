package com.jachai.talent.repository;

import com.jachai.talent.model.HiringCampaign;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HiringCampaignRepository extends MongoRepository<HiringCampaign, String> {
    Optional<HiringCampaign> findByIdAndOrganizationId(String id, String organizationId);
    Page<HiringCampaign> findByOrganizationId(String organizationId, Pageable pageable);
}
