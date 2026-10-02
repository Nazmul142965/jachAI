package com.jachai.organization.dto;

import com.jachai.assessment.model.ResultReleasePolicy;
import jakarta.validation.constraints.NotNull;

public record UpdateOrganizationSettingsRequest(@NotNull ResultReleasePolicy defaultResultReleasePolicy) { }
