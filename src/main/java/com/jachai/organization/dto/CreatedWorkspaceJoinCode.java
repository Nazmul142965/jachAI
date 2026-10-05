package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;
import java.time.Instant;

public record CreatedWorkspaceJoinCode(String id, String code, OrganizationRole role, Instant expiresAt) { }
