package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;
import jakarta.validation.constraints.NotNull;

public record ChangeMemberRoleRequest(@NotNull OrganizationRole role) { }
