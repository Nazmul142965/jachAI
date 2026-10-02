package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;

public record MemberResponse(String userId, String email, String displayName,
                             OrganizationRole role, boolean owner, boolean active) {
    public MemberResponse(String userId, String email, String displayName, OrganizationRole role) {
        this(userId, email, displayName, role, false, true);
    }
}
