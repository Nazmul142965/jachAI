package com.jachai.platform.service;

import com.jachai.billing.dto.PaymentOrderView;
import com.jachai.billing.model.Subscription;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.organization.repository.OrganizationRepository;
import com.jachai.platform.dto.PlatformBillingView;
import com.jachai.shared.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformBillingSupportService {
    private static final int PAGE_SIZE = 20;

    private final PlatformAdminService platform;
    private final OrganizationRepository organizations;
    private final SubscriptionRepository subscriptions;
    private final PaymentOrderRepository orders;

    public PlatformBillingView billing(String actorUserId, String organizationId) {
        requireAccess(actorUserId, organizationId);
        Subscription subscription = subscriptions.findById(organizationId).orElse(null);
        if (subscription == null) {
            return new PlatformBillingView(organizationId, false, null, null, null, null, null);
        }
        return new PlatformBillingView(organizationId, true, subscription.tier(), subscription.status(),
                subscription.currentPeriodEnd(), subscription.lastPaidOrderId(), subscription.updatedAt());
    }

    public Page<PaymentOrderView> orders(String actorUserId, String organizationId, int page) {
        requireAccess(actorUserId, organizationId);
        if (page < 0 || page > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be between 0 and 500");
        }
        return orders.findByOrganizationId(organizationId,
                        PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(PaymentOrderView::from);
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
}
