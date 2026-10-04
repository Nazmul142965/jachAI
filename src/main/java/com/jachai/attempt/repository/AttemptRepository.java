package com.jachai.attempt.repository;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AttemptRepository extends MongoRepository<Attempt, String> {
    Optional<Attempt> findByIdAndOrganizationId(String id, String organizationId);
    Optional<Attempt> findByIdAndOrganizationIdAndUserId(String id, String organizationId, String userId);
    List<Attempt> findByAssignmentIdAndOrganizationIdAndUserId(
            String assignmentId, String organizationId, String userId);
    boolean existsByOrganizationIdAndUserIdAndStatusAndDeadlineAfter(
            String organizationId, String userId, AttemptStatus status, java.time.Instant deadline);
    List<Attempt> findByOrganizationIdAndAssignmentIdInAndStatus(
            String organizationId, List<String> assignmentIds, AttemptStatus status);
    Page<Attempt> findByOrganizationIdAndStatusAndResultReleasedAtIsNull(
            String organizationId, AttemptStatus status, Pageable pageable);
    boolean existsByOrganizationIdAndAssignmentIdAndStatus(
            String organizationId, String assignmentId, AttemptStatus status);
}
