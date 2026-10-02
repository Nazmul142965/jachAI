package com.jachai.attempt.repository;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AttemptRepository extends MongoRepository<Attempt, String> {
    Optional<Attempt> findByIdAndOrganizationId(String id, String organizationId);
    Optional<Attempt> findByIdAndOrganizationIdAndUserId(String id, String organizationId, String userId);
    List<Attempt> findByAssignmentIdAndOrganizationIdAndUserId(
            String assignmentId, String organizationId, String userId);
    List<Attempt> findByOrganizationIdAndUserIdAndStatus(
            String organizationId, String userId, AttemptStatus status);
    List<Attempt> findByOrganizationIdAndAssignmentIdInAndStatus(
            String organizationId, List<String> assignmentIds, AttemptStatus status);
}
