package com.jachai.support.dto;

import com.jachai.support.type.TicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateSupportTicketRequest(
        @NotBlank(message = "category is required")
        TicketCategory category,

        @NotBlank(message = "subject is required")
        @Size(max = 200, message = "subject must be 200 characters or fewer")
        String subject,

        @NotBlank(message = "description is required")
        @Size(max = 4000, message = "description must be 4000 characters or fewer")
        String description,

        /** Optional. Must be an organization the requester actually belongs to. */
        String organizationId
) {}