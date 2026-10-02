package com.jachai.organization.dto;

import jakarta.validation.constraints.NotBlank;

public record TransferOwnershipRequest(@NotBlank String targetUserId) { }
