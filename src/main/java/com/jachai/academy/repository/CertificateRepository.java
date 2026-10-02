package com.jachai.academy.repository;

import com.jachai.academy.model.Certificate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CertificateRepository extends MongoRepository<Certificate, String> {
    Optional<Certificate> findByIdAndOrganizationId(String id, String organizationId);
    Optional<Certificate> findByAttemptIdAndOrganizationId(String attemptId, String organizationId);
    Optional<Certificate> findByVerificationCode(String verificationCode);
    Page<Certificate> findByOrganizationIdAndLearnerUserId(
            String organizationId, String learnerUserId, Pageable pageable);
}
