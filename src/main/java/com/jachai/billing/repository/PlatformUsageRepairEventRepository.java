package com.jachai.billing.repository;

import com.jachai.billing.model.PlatformUsageRepairEvent;
import com.jachai.billing.model.PlatformUsageRepairStage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlatformUsageRepairEventRepository extends MongoRepository<PlatformUsageRepairEvent, String> {
    Page<PlatformUsageRepairEvent> findByOrganizationId(String organizationId, Pageable pageable);
    long countByStage(PlatformUsageRepairStage stage);
}
