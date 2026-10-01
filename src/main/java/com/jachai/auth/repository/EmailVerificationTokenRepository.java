package com.jachai.auth.repository;

import com.jachai.auth.model.EmailVerificationToken;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailVerificationTokenRepository extends MongoRepository<EmailVerificationToken, String> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
    void deleteByUserId(String userId);
}
