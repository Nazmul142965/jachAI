package com.jachai.assessment.repository;

import com.jachai.assessment.model.Assessment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AssessmentRepository extends MongoRepository<Assessment, String> {
    Optional<Assessment> findByIdAndOrganizationId(String id, String organizationId);
    List<Assessment> findByOrganizationId(String organizationId);
}
