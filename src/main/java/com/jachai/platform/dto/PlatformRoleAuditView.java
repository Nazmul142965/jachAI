package com.jachai.platform.dto;

import com.jachai.platform.model.PlatformRoleAction;
import java.time.Instant;

public record PlatformRoleAuditView(String id, String targetUserId, String actorUserId,
                                    PlatformRoleAction action, long revision, Instant occurredAt) { }
