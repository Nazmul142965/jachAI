package com.jachai.practice.repository;

import com.jachai.practice.model.PracticeSession;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PracticeSessionRepository extends MongoRepository<PracticeSession, String> {
    Optional<PracticeSession> findByIdAndUserId(String id, String userId);
    Page<PracticeSession> findByUserId(String userId, Pageable pageable);
}
