package com.jachai.shared.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditEventRepository extends MongoRepository<AuditEvent, String> {
    Page<AuditEvent> findByOrganizationIdAndTargetTypeAndTargetId(
            String organizationId, String targetType, String targetId, Pageable pageable);
}
