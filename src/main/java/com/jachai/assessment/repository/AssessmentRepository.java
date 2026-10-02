package com.jachai.assessment.repository;

import com.jachai.assessment.model.Assessment;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.jachai.assessment.model.AssessmentStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AssessmentRepository extends MongoRepository<Assessment, String> {
    Optional<Assessment> findByIdAndOrganizationId(String id, String organizationId);
    Page<Assessment> findByOrganizationId(String organizationId, Pageable pageable);
    Page<Assessment> findByOrganizationIdAndStatus(String organizationId, AssessmentStatus status, Pageable pageable);
}
