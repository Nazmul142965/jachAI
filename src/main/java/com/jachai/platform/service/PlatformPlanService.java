package com.jachai.platform.service;

import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.dto.ManualPlanView;
import com.jachai.platform.dto.ManualPlanAuditView;
import com.jachai.platform.model.PlatformPlanAction;
import com.jachai.platform.model.PlatformPlanEvent;
import com.jachai.platform.repository.PlatformPlanEventRepository;
import com.jachai.shared.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlatformPlanService {
    private static final Duration MAX_GRANT = Duration.ofDays(30);
    private final PlatformAdminService platform;
    private final OrganizationRepository organizations;
    private final SubscriptionRepository subscriptions;
    private final PlatformPlanEventRepository events;
    private final Clock clock;

    @Autowired
    public PlatformPlanService(PlatformAdminService platform, OrganizationRepository organizations,
                               SubscriptionRepository subscriptions, PlatformPlanEventRepository events) {
        this(platform, organizations, subscriptions, events, Clock.systemUTC());
    }

    PlatformPlanService(PlatformAdminService platform, OrganizationRepository organizations,
                        SubscriptionRepository subscriptions, PlatformPlanEventRepository events, Clock clock) {
        this.platform = platform;
        this.organizations = organizations;
        this.subscriptions = subscriptions;
        this.events = events;
        this.clock = clock;
    }

    public ManualPlanView current(String actorUserId, String organizationId) {
        requireAccess(actorUserId, organizationId);
        return ManualPlanView.from(organizationId, latest(organizationId).orElse(null), clock.instant());
    }

    public ManualPlanView grant(String actorUserId, String organizationId, Instant expiresAt, String reason) {
        requireAccess(actorUserId, organizationId);
        Instant now = clock.instant();
        String explanation = validReason(reason);
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_GRANT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRANT_PERIOD",
                    "A manual TEAM grant must end within 30 days");
        }
        // MongoDB stores dates to millisecond precision. Compare and persist the same value so an
        // identical retry cannot create a second audited grant solely because nanos were discarded.
        Instant storedExpiry = expiresAt.truncatedTo(ChronoUnit.MILLIS);
        if (!storedExpiry.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRANT_PERIOD",
                    "A manual TEAM grant must end within 30 days");
        }
        Subscription paid = subscriptions.findById(organizationId).orElse(null);
        if (paid != null && paid.tier() == PlanTier.TEAM && paid.status() == SubscriptionStatus.ACTIVE
                && paid.currentPeriodEnd() != null && paid.currentPeriodEnd().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "PAID_PLAN_ACTIVE",
                    "An active paid TEAM subscription cannot be replaced with a manual grant");
        }
        PlatformPlanEvent previous = latest(organizationId).orElse(null);
        if (previous != null && previous.action() == PlatformPlanAction.GRANTED
                && now.isBefore(previous.expiresAt()) && previous.expiresAt().equals(storedExpiry)
                && previous.reason().equals(explanation)) {
            return ManualPlanView.from(organizationId, previous, now);
        }
        return append(actorUserId, organizationId, PlatformPlanAction.GRANTED, storedExpiry,
                explanation, previous, now);
    }

    public ManualPlanView revoke(String actorUserId, String organizationId, String reason) {
        requireAccess(actorUserId, organizationId);
        Instant now = clock.instant();
        String explanation = validReason(reason);
        PlatformPlanEvent previous = latest(organizationId).orElse(null);
        if (previous == null || previous.action() != PlatformPlanAction.GRANTED
                || !now.isBefore(previous.expiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_MANUAL_GRANT",
                    "There is no active manual TEAM grant to revoke");
        }
        return append(actorUserId, organizationId, PlatformPlanAction.REVOKED, null,
                explanation, previous, now);
    }

    public Page<ManualPlanAuditView> history(String actorUserId, String organizationId, int page) {
        requireAccess(actorUserId, organizationId);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        return events.findByOrganizationId(organizationId,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "revision")))
                .map(ManualPlanAuditView::from);
    }

    private ManualPlanView append(String actorUserId, String organizationId, PlatformPlanAction action,
                                  Instant expiresAt, String reason, PlatformPlanEvent previous, Instant now) {
        PlatformPlanEvent event = new PlatformPlanEvent(UUID.randomUUID().toString(), organizationId,
                actorUserId, action, expiresAt, reason,
                previous == null ? 1 : previous.revision() + 1, now);
        try {
            return ManualPlanView.from(organizationId, events.insert(event), now);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_CHANGED",
                    "Someone else changed this free plan at the same time. Reload the page and try again.");
        }
    }

    private Optional<PlatformPlanEvent> latest(String organizationId) {
        return events.findFirstByOrganizationIdOrderByRevisionDesc(organizationId);
    }

    private void requireAccess(String actorUserId, String organizationId) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found");
        }
    }

    private String validReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < 10 || trimmed.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASON",
                    "A 10–500 character reason is required");
        }
        return trimmed;
    }
}
