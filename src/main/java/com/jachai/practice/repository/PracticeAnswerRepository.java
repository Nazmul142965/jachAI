package com.jachai.practice.repository;

import com.jachai.practice.model.PracticeAnswer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PracticeAnswerRepository extends MongoRepository<PracticeAnswer, String> {
    Page<PracticeAnswer> findByUserId(String userId, Pageable pageable);
}
