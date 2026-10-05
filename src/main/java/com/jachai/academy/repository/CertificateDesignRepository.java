package com.jachai.academy.repository;

import com.jachai.academy.model.CertificateDesign;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CertificateDesignRepository extends MongoRepository<CertificateDesign, String> {
    Optional<CertificateDesign> findByIdAndOrganizationId(String id, String organizationId);
    long countByOrganizationIdAndCreatedAtAfter(String organizationId, Instant after);
}
