package com.jachai.organization.repository;

import com.jachai.organization.model.OrganizationInvitation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrganizationInvitationRepository extends MongoRepository<OrganizationInvitation, String> {
    Optional<OrganizationInvitation> findByTokenHash(String tokenHash);
    Optional<OrganizationInvitation> findByIdAndOrganizationId(String id, String organizationId);
    Optional<OrganizationInvitation> findByOrganizationIdAndEmail(String organizationId, String email);
    Page<OrganizationInvitation> findByOrganizationId(String organizationId, Pageable pageable);
}
