package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.WorkspaceJoinCode;
import java.time.Instant;

public record WorkspaceJoinCodeView(String id, OrganizationRole role, Instant createdAt,
                                    Instant expiresAt, Instant revokedAt, long redemptions) {
    public static WorkspaceJoinCodeView from(WorkspaceJoinCode code, long completedRedemptions) {
        return new WorkspaceJoinCodeView(code.id(), code.role(), code.createdAt(),
                code.expiresAt(), code.revokedAt(), code.redemptions() + completedRedemptions);
    }
}
