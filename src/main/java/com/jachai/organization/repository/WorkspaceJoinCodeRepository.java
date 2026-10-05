package com.jachai.organization.repository;

import com.jachai.organization.model.WorkspaceJoinCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkspaceJoinCodeRepository extends MongoRepository<WorkspaceJoinCode, String> {
    Optional<WorkspaceJoinCode> findByCodeHash(String codeHash);
    Optional<WorkspaceJoinCode> findByIdAndOrganizationId(String id, String organizationId);
    Page<WorkspaceJoinCode> findByOrganizationId(String organizationId, Pageable pageable);
    long countByOrganizationIdAndRevokedAtIsNullAndExpiresAtAfter(String organizationId, Instant now);
}
