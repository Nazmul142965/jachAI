package com.jachai.assessment.repository;

import com.jachai.assessment.model.AssessmentVersion;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AssessmentVersionRepository extends MongoRepository<AssessmentVersion, String> {
    Optional<AssessmentVersion> findByIdAndOrganizationId(String id, String organizationId);
    Optional<AssessmentVersion> findByAssessmentIdAndVersionAndOrganizationId(
            String assessmentId, int version, String organizationId);
}
