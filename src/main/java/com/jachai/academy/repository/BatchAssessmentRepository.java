package com.jachai.academy.repository;

import com.jachai.academy.model.BatchAssessment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BatchAssessmentRepository extends MongoRepository<BatchAssessment, String> {
    Optional<BatchAssessment> findByIdAndOrganizationIdAndBatchId(
            String id, String organizationId, String batchId);
    Page<BatchAssessment> findByOrganizationIdAndBatchId(String organizationId, String batchId,
                                                          Pageable pageable);
    List<BatchAssessment> findByOrganizationIdAndBatchId(String organizationId, String batchId);
}
