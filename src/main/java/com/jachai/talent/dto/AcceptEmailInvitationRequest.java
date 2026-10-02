package com.jachai.talent.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptEmailInvitationRequest(@NotBlank String token) { }
