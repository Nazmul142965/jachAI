package com.jachai.billing.service;

import com.jachai.billing.dto.PlanEntitlements;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.billing.model.UsageWindow;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntitlementService {
    private final SubscriptionRepository subscriptions;
    private final MongoTemplate mongo;
    private final TenantAccessService access;

    public PlanEntitlements forAdmin(String actorId, String organizationId) {
        access.requireRole(actorId, organizationId, OrganizationRole.ORG_ADMIN);
        return plan(organizationId);
    }

    public PlanEntitlements plan(String organizationId) {
        Subscription subscription = subscriptions.findById(organizationId).orElse(null);
        PlanTier tier = subscription != null && subscription.status() == SubscriptionStatus.ACTIVE
                && subscription.tier() == PlanTier.TEAM
                && subscription.currentPeriodEnd() != null
                && subscription.currentPeriodEnd().isAfter(Instant.now())
                ? PlanTier.TEAM : PlanTier.FREE;
        return PlanEntitlements.forTier(tier);
    }

    public void requireAi(String organizationId) {
        if (!plan(organizationId).aiEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLAN_FEATURE_UNAVAILABLE", "AI is not available on this plan");
        }
    }

    public String reserve(String organizationId, UsageMetric metric) {
        long limit = plan(organizationId).limitFor(metric);
        if (limit < 1) throw limitReached(metric);
        Instant now = Instant.now();
        UsageWindow window = UsageWindow.of(organizationId, metric, now);
        Query query = Query.query(Criteria.where("_id").is(window.bucketId()).and("used").lt(limit));
        Update update = new Update().inc("used", 1)
                .set("lastChangedAt", now)
                .setOnInsert("organizationId", organizationId)
                .setOnInsert("metric", metric)
                .setOnInsert("window", window.window())
                .setOnInsert("createdAt", now);
        if (window.expiresAt() != null) update.setOnInsert("expiresAt", window.expiresAt());
        try {
            UsageBucket reserved = mongo.findAndModify(query, update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true), UsageBucket.class);
            if (reserved == null) throw limitReached(metric);
        } catch (DuplicateKeyException exception) {
            throw limitReached(metric);
        }
        return window.bucketId();
    }

    public void refund(String bucketId) {
        if (bucketId == null) return;
        mongo.updateFirst(Query.query(Criteria.where("_id").is(bucketId).and("used").gt(0)),
                new Update().inc("used", -1L).set("lastChangedAt", Instant.now()), UsageBucket.class);
    }

    private ApiException limitReached(UsageMetric metric) {
        return new ApiException(HttpStatus.CONFLICT, "PLAN_LIMIT_REACHED",
                "Plan limit reached for " + metric.name().toLowerCase().replace('_', ' '));
    }
}
