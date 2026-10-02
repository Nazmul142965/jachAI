package com.jachai.academy.repository;

import com.jachai.academy.model.BatchEnrollment;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BatchEnrollmentRepository extends MongoRepository<BatchEnrollment, String> {
    Optional<BatchEnrollment> findByOrganizationIdAndBatchIdAndLearnerUserId(
            String organizationId, String batchId, String learnerUserId);
    Page<BatchEnrollment> findByOrganizationIdAndBatchIdAndActiveTrue(
            String organizationId, String batchId, Pageable pageable);
    long countByOrganizationIdAndBatchIdAndActiveTrue(String organizationId, String batchId);
    Page<BatchEnrollment> findByOrganizationIdAndLearnerUserIdAndActiveTrue(
            String organizationId, String learnerUserId, Pageable pageable);
}
