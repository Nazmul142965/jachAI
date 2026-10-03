package com.jachai.platform.repository;

import com.jachai.platform.model.PlatformPlanEvent;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlatformPlanEventRepository extends MongoRepository<PlatformPlanEvent, String> {
    Optional<PlatformPlanEvent> findFirstByOrganizationIdOrderByRevisionDesc(String organizationId);
    Page<PlatformPlanEvent> findByOrganizationId(String organizationId, Pageable pageable);
}
