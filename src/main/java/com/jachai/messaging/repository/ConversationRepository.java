package com.jachai.messaging.repository;

import com.jachai.messaging.model.Conversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConversationRepository extends MongoRepository<Conversation, String> {
    Optional<Conversation> findByIdAndOrganizationId(String id, String organizationId);
    Optional<Conversation> findByOrganizationIdAndInstructorUserIdAndLearnerUserId(
            String organizationId, String instructorUserId, String learnerUserId);
    Page<Conversation> findByOrganizationIdAndInstructorUserId(
            String organizationId, String instructorUserId, Pageable pageable);
    Page<Conversation> findByOrganizationIdAndLearnerUserId(
            String organizationId, String learnerUserId, Pageable pageable);
}
