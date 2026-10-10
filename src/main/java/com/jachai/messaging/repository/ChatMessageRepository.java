package com.jachai.messaging.repository;

import com.jachai.messaging.model.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {
    Page<ChatMessage> findByOrganizationIdAndConversationId(
            String organizationId, String conversationId, Pageable pageable);
    long countByOrganizationIdAndConversationId(String organizationId, String conversationId);

    /** Messages not written by {@code senderUserId} after {@code after}: what that person has not read. */
    long countByOrganizationIdAndConversationIdAndSenderUserIdNotAndSentAtAfter(
            String organizationId, String conversationId, String senderUserId, java.time.Instant after);
}
