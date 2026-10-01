package com.jachai.academy.repository;

import com.jachai.academy.model.Certificate;
import java.util.Optional;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CertificateRepository extends MongoRepository<Certificate, String> {
    Optional<Certificate> findByIdAndOrganizationId(String id, String organizationId);
    Optional<Certificate> findByAttemptIdAndOrganizationId(String attemptId, String organizationId);
    Optional<Certificate> findByVerificationCode(String verificationCode);
    List<Certificate> findByOrganizationIdAndLearnerUserId(String organizationId, String learnerUserId);
}
