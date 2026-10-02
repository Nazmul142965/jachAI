package com.jachai.attempt.repository;

import com.jachai.attempt.model.Assignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AssignmentRepository extends MongoRepository<Assignment, String> {
    Optional<Assignment> findByIdAndOrganizationId(String id, String organizationId);
    Optional<Assignment> findByIdAndOrganizationIdAndAssignedUserId(
            String id, String organizationId, String assignedUserId);
    List<Assignment> findByOrganizationIdAndAssignedUserId(String organizationId, String assignedUserId);
    Page<Assignment> findByOrganizationIdAndCampaignId(String organizationId, String campaignId, Pageable pageable);
    boolean existsByOrganizationIdAndCampaignIdAndAssignedUserId(
            String organizationId, String campaignId, String assignedUserId);
    Page<Assignment> findByOrganizationIdAndBatchAssessmentId(
            String organizationId, String batchAssessmentId, Pageable pageable);
}
