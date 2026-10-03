package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrganizationInvitationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull OrganizationRole role) { }
