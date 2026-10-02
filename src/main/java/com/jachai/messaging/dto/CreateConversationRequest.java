package com.jachai.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(@NotBlank @Size(max = 100) String partnerUserId) { }
