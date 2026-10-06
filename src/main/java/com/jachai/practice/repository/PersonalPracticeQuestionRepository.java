package com.jachai.practice.repository;

import com.jachai.practice.model.PersonalPracticeQuestion;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PersonalPracticeQuestionRepository extends MongoRepository<PersonalPracticeQuestion, String> {
    Optional<PersonalPracticeQuestion> findByIdAndUserId(String id, String userId);
    Page<PersonalPracticeQuestion> findByUserId(String userId, Pageable pageable);
}
