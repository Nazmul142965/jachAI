package com.jachai.messaging.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("academy_chat_messages")
@CompoundIndex(name = "message_org_conversation_sent",
        def = "{'organizationId': 1, 'conversationId': 1, 'sentAt': -1}")
public record ChatMessage(@Id String id, String organizationId, String conversationId,
                          String senderUserId, String body, Instant sentAt,
                          @Indexed(expireAfter = "0s") Instant expiresAt) { }
