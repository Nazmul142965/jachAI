package com.jachai.support.dto;

import java.time.Instant;

public record SupportTicketReplyView(
        String id,
        String authorUserId,
        boolean publicReply,
        String body,
        Instant createdAt
) {}