package com.jachai.auth.repository;

import com.jachai.auth.model.RevokedAccessToken;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RevokedAccessTokenRepository extends MongoRepository<RevokedAccessToken, String> { }
