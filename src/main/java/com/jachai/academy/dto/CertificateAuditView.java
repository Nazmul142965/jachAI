package com.jachai.academy.dto;

import com.jachai.shared.audit.AuditAction;
import com.jachai.shared.audit.AuditEvent;
import java.time.Instant;

public record CertificateAuditView(AuditAction action, String actorUserId, Instant occurredAt) {
    public static CertificateAuditView from(AuditEvent event) {
        return new CertificateAuditView(event.action(), event.actorUserId(), event.occurredAt());
    }
}
