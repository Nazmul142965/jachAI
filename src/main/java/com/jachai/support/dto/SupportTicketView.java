package com.jachai.support.dto;

import com.jachai.support.type.TicketCategory;
import com.jachai.support.type.TicketStatus;
import java.time.Instant;

public record SupportTicketView(
        String id,
        String referenceNumber,
        TicketCategory category,
        String subject,
        String description,
        TicketStatus status,
        String organizationId,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        String lastReplyBodyPreview,
        Instant lastReplyAt,
        int publicReplyCount,
        int internalNoteCount
) {}