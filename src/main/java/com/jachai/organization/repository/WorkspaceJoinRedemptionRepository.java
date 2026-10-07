package com.jachai.organization.repository;

import com.jachai.organization.model.WorkspaceJoinRedemption;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkspaceJoinRedemptionRepository extends MongoRepository<WorkspaceJoinRedemption, String> {
    long countByCodeIdAndCompletedAtIsNotNull(String codeId);
}
