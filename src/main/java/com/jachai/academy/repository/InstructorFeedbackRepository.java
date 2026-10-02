package com.jachai.academy.repository;

import com.jachai.academy.model.InstructorFeedback;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InstructorFeedbackRepository extends MongoRepository<InstructorFeedback, String> {
    Optional<InstructorFeedback> findByOrganizationIdAndAttemptId(String organizationId, String attemptId);
    Optional<InstructorFeedback> findByOrganizationIdAndAttemptIdAndLearnerUserId(
            String organizationId, String attemptId, String learnerUserId);
}
