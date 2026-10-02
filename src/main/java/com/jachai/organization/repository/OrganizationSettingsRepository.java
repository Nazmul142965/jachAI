package com.jachai.organization.repository;

import com.jachai.organization.model.OrganizationSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrganizationSettingsRepository extends MongoRepository<OrganizationSettings, String> { }
