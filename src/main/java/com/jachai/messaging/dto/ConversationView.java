package com.jachai.messaging.dto;

import java.time.Instant;

public record ConversationView(String id, String instructorUserId, String learnerUserId,
                               String counterpartDisplayName, Instant createdAt) { }
