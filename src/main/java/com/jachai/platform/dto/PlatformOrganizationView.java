package com.jachai.platform.dto;

import com.jachai.organization.model.OrganizationType;
import java.time.Instant;

public record PlatformOrganizationView(String id, String name, OrganizationType type,
                                       String ownerUserId, Instant createdAt) {
}
