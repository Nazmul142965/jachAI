package com.jachai.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateSupportReplyRequest(
        @NotBlank(message = "body is required")
        @Size(max = 4000, message = "body must be 4000 characters or fewer")
        String body,

        /**
         * True for a public reply visible to the ticket requester. False for an
         * internal operator note visible only to PLATFORM_ADMIN operators.
         * Defaults to true for customer-facing routes and false for operator routes.
         */
        Boolean publicReply
) {}