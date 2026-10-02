package com.jachai.organization.repository;

import com.jachai.organization.model.Organization;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface OrganizationRepository extends MongoRepository<Organization, String> {
    List<Organization> findByOwnerUserId(String ownerUserId);
}
