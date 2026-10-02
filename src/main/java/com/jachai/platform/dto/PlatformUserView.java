package com.jachai.platform.dto;

import java.time.Instant;

public record PlatformUserView(String id, String email, String displayName,
                               boolean emailVerified, boolean disabled, Instant createdAt) {
}
