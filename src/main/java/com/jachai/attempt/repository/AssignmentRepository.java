package com.jachai.attempt.repository;

import com.jachai.attempt.model.Assignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AssignmentRepository extends MongoRepository<Assignment, String> {
    Optional<Assignment> findByIdAndOrganizationIdAndAssignedUserId(
            String id, String organizationId, String assignedUserId);
    List<Assignment> findByOrganizationIdAndAssignedUserId(String organizationId, String assignedUserId);
}
