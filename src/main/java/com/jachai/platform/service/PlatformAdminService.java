package com.jachai.platform.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.dto.PlatformOrganizationView;
import com.jachai.platform.dto.PlatformUserView;
import com.jachai.shared.error.ApiException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlatformAdminService {
    private static final int PAGE_SIZE = 20;

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final Set<String> adminUserIds;

    public PlatformAdminService(UserRepository users, OrganizationRepository organizations,
                                @Value("${app.platform.admin-user-ids:}") String configuredIds) {
        this.users = users;
        this.organizations = organizations;
        this.adminUserIds = Arrays.stream(configuredIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Page<PlatformOrganizationView> organizations(String actorUserId, int page) {
        requireAdmin(actorUserId);
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must not be negative");
        }
        return organizations.findAll(PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toOrganizationView);
    }

    public PlatformUserView user(String actorUserId, String targetUserId) {
        requireAdmin(actorUserId);
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));
        return new PlatformUserView(target.id(), target.email(), target.displayName(),
                target.emailVerified(), target.disabled(), target.createdAt());
    }

    public boolean isPlatformAdmin(String actorUserId) {
        return adminUserIds.contains(actorUserId) && users.findById(actorUserId)
                .filter(user -> user.emailVerified() && !user.disabled()).isPresent();
    }

    private void requireAdmin(String actorUserId) {
        if (!isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
    }

    private PlatformOrganizationView toOrganizationView(Organization organization) {
        return new PlatformOrganizationView(organization.id(), organization.name(), organization.type(),
                organization.ownerUserId(), organization.createdAt());
    }
}
