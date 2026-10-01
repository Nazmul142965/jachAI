package com.jachai.organization.repository;

import com.jachai.organization.model.Membership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MembershipRepository extends MongoRepository<Membership, String> {
    Optional<Membership> findByUserIdAndOrganizationIdAndActiveTrue(String userId, String organizationId);
    Optional<Membership> findByUserIdAndOrganizationId(String userId, String organizationId);
    List<Membership> findByUserIdAndActiveTrue(String userId);
    List<Membership> findByOrganizationIdAndActiveTrue(String organizationId);
}
