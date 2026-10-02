package com.jachai.question.repository;

import com.jachai.question.model.Question;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.jachai.question.model.QuestionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface QuestionRepository extends MongoRepository<Question, String> {
    Optional<Question> findByIdAndOrganizationId(String id, String organizationId);
    Page<Question> findByOrganizationId(String organizationId, Pageable pageable);
    Page<Question> findByOrganizationIdAndStatus(String organizationId, QuestionStatus status, Pageable pageable);
}
