package com.jachai.messaging.dto;

import com.jachai.messaging.model.ChatMessage;
import java.time.Instant;

public record ChatMessageView(String id, String senderUserId, String body, Instant sentAt) {
    public static ChatMessageView from(ChatMessage message) {
        return new ChatMessageView(message.id(), message.senderUserId(),
                message.body(), message.sentAt());
    }
}
