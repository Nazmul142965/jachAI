package com.jachai.messaging.dto;

import java.time.Instant;

public record ConversationView(String id, String instructorUserId, String learnerUserId,
                               String counterpartDisplayName, Instant createdAt,
                               /** Messages from the other person that this person has not opened yet. */
                               int unread, Instant lastMessageAt, String lastMessagePreview) { }
