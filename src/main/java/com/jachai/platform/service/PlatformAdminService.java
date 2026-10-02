package com.jachai.platform.service;

import com.jachai.auth.model.User;
import com.jachai.auth.repository.UserRepository;
import com.jachai.organization.model.Organization;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.dto.PlatformOrganizationView;
import com.jachai.platform.dto.PlatformRoleAuditView;
import com.jachai.platform.dto.PlatformRoleView;
import com.jachai.platform.dto.PlatformUserView;
import com.jachai.platform.model.PlatformRoleAction;
import com.jachai.platform.model.PlatformRoleEvent;
import com.jachai.platform.repository.PlatformRoleEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PlatformAdminService {
    private static final int PAGE_SIZE = 20;

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final PlatformRoleEventRepository roleEvents;
    private final Set<String> adminUserIds;

    public PlatformAdminService(UserRepository users, OrganizationRepository organizations,
                                PlatformRoleEventRepository roleEvents,
                                @Value("${app.platform.admin-user-ids:}") String configuredIds) {
        this.users = users;
        this.organizations = organizations;
        this.roleEvents = roleEvents;
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
        User actor = users.findById(actorUserId).orElse(null);
        return actor != null && actor.emailVerified() && !actor.disabled()
                && (adminUserIds.contains(actorUserId) || latestRole(actorUserId));
    }

    public PlatformRoleView role(String actorUserId, String targetUserId) {
        requireAdmin(actorUserId);
        return roleView(requireUser(targetUserId));
    }

    public PlatformRoleView changeRole(String actorUserId, String targetUserId, boolean grant) {
        requireAdmin(actorUserId);
        if (actorUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_ROLE_CHANGE",
                    "An administrator cannot change their own platform role");
        }
        if (adminUserIds.contains(targetUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, "BOOTSTRAP_ADMIN",
                    "An environment-configured administrator cannot be changed through the API");
        }
        User target = requireUser(targetUserId);
        if (grant && (!target.emailVerified() || target.disabled())) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_UNAVAILABLE",
                    "Only a verified, active account can become a platform administrator");
        }
        Optional<PlatformRoleEvent> latest = latestEvent(targetUserId);
        boolean current = latest.map(event -> event.action() == PlatformRoleAction.GRANTED).orElse(false);
        if (current != grant) {
            long revision = latest.map(event -> event.revision() + 1).orElse(1L);
            try {
                roleEvents.insert(new PlatformRoleEvent(UUID.randomUUID().toString(), targetUserId,
                        actorUserId, grant ? PlatformRoleAction.GRANTED : PlatformRoleAction.REVOKED,
                        revision, Instant.now()));
            } catch (DuplicateKeyException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "ROLE_CHANGED",
                        "Platform role changed concurrently; reload and retry");
            }
        }
        return roleView(target);
    }

    public Page<PlatformRoleAuditView> roleHistory(String actorUserId, String targetUserId, int page) {
        requireAdmin(actorUserId);
        requireUser(targetUserId);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        return roleEvents.findByTargetUserId(targetUserId, PageRequest.of(page, PAGE_SIZE,
                        Sort.by(Sort.Direction.DESC, "revision")))
                .map(event -> new PlatformRoleAuditView(event.id(), event.targetUserId(),
                        event.actorUserId(), event.action(), event.revision(), event.occurredAt()));
    }

    private User requireUser(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "User not found"));
    }

    private PlatformRoleView roleView(User user) {
        boolean bootstrap = adminUserIds.contains(user.id());
        boolean effective = user.emailVerified() && !user.disabled()
                && (bootstrap || latestRole(user.id()));
        return new PlatformRoleView(user.id(), effective, bootstrap);
    }

    private boolean latestRole(String userId) {
        return latestEvent(userId)
                .map(event -> event.action() == PlatformRoleAction.GRANTED).orElse(false);
    }

    private Optional<PlatformRoleEvent> latestEvent(String userId) {
        return roleEvents.findFirstByTargetUserIdOrderByRevisionDesc(userId);
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
