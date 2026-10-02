package com.jachai.organization.dto;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.model.OrganizationType;

public record OrganizationResponse(String id, String name, OrganizationType type,
                                   OrganizationRole myRole, boolean owner) {
}
