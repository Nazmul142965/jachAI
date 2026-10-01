package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;

public record MemberResponse(String userId, String email, String displayName, OrganizationRole role) { }
