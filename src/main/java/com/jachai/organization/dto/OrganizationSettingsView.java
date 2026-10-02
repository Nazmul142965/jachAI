package com.jachai.organization.dto;

import com.jachai.assessment.model.ResultReleasePolicy;
import java.time.Instant;

public record OrganizationSettingsView(ResultReleasePolicy defaultResultReleasePolicy,
                                       String updatedByUserId, Instant updatedAt) { }
