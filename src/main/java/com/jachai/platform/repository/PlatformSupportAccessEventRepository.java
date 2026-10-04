package com.jachai.platform.repository;

import com.jachai.platform.model.PlatformSupportAccessEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlatformSupportAccessEventRepository extends MongoRepository<PlatformSupportAccessEvent, String> {
    Page<PlatformSupportAccessEvent> findByOrganizationId(String organizationId, Pageable pageable);
}
