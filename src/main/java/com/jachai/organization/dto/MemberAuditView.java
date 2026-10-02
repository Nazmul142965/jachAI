package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

public record MemberAuditView(AuditAction action, String actorUserId, OrganizationRole previousRole,
                              OrganizationRole role, Instant occurredAt) {
    public static MemberAuditView from(AuditEvent event) {
        String previous = event.details().get("previousRole");
        return new MemberAuditView(event.action(), event.actorUserId(),
                previous == null ? null : OrganizationRole.valueOf(previous),
                OrganizationRole.valueOf(event.details().get("role")), event.occurredAt());
    }
}
