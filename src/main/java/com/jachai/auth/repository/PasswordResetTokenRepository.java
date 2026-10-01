package com.jachai.auth.repository;

import com.jachai.auth.model.PasswordResetToken;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PasswordResetTokenRepository extends MongoRepository<PasswordResetToken, String> {
    void deleteByUserId(String userId);
}
