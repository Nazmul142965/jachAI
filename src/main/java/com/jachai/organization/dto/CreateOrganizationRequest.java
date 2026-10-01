package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotNull OrganizationType type) {
}
