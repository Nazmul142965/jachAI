package com.jachai.organization.repository;

import com.jachai.organization.model.Membership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.jachai.organization.model.OrganizationRole;

public interface MembershipRepository extends MongoRepository<Membership, String> {
    Optional<Membership> findByUserIdAndOrganizationIdAndActiveTrue(String userId, String organizationId);
    Optional<Membership> findByUserIdAndOrganizationId(String userId, String organizationId);
    List<Membership> findByUserIdAndActiveTrue(String userId);
    List<Membership> findByOrganizationIdAndActiveTrue(String organizationId);
    Page<Membership> findByOrganizationIdAndRoleAndActiveTrue(
            String organizationId, OrganizationRole role, Pageable pageable);
}
