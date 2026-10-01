package com.jachai.question.repository;

import com.jachai.question.model.Question;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface QuestionRepository extends MongoRepository<Question, String> {
    Optional<Question> findByIdAndOrganizationId(String id, String organizationId);
    List<Question> findByOrganizationId(String organizationId);
}
