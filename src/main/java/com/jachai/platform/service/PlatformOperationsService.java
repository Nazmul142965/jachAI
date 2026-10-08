package com.jachai.platform.service;

import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.model.PlatformUsageRepairStage;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.PlatformUsageRepairEventRepository;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.repository.EmailJobRepository;
import com.jachai.platform.dto.PlatformOperationsView;
import com.jachai.platform.dto.PlatformSandboxPaymentSummary;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlatformOperationsService {
    private static final Duration EMAIL_OVERDUE = Duration.ofMinutes(15);
    private static final Duration CHECKOUT_OLD = Duration.ofDays(1);
    private static final Duration VERIFIED_STALE = Duration.ofMinutes(5);

    private final PlatformAdminService platform;
    private final EmailJobRepository emailJobs;
    private final PaymentOrderRepository paymentOrders;
    private final PlatformUsageRepairEventRepository repairEvents;
    private final Clock clock;

    @Autowired
    public PlatformOperationsService(PlatformAdminService platform, EmailJobRepository emailJobs,
                                     PaymentOrderRepository paymentOrders,
                                     PlatformUsageRepairEventRepository repairEvents) {
        this(platform, emailJobs, paymentOrders, repairEvents, Clock.systemUTC());
    }

    PlatformOperationsService(PlatformAdminService platform, EmailJobRepository emailJobs,
                              PaymentOrderRepository paymentOrders,
                              PlatformUsageRepairEventRepository repairEvents, Clock clock) {
        this.platform = platform;
        this.emailJobs = emailJobs;
        this.paymentOrders = paymentOrders;
        this.repairEvents = repairEvents;
        this.clock = clock;
    }

    public PlatformOperationsView summary(String actorUserId) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
        Instant now = clock.instant();
        long requested = repairEvents.countByStage(PlatformUsageRepairStage.REQUESTED);
        long completed = repairEvents.countByStage(PlatformUsageRepairStage.COMPLETED);
        return new PlatformOperationsView(now,
                emailJobs.countByStatus(EmailJobStatus.FAILED),
                emailJobs.countByStatusAndNextAttemptAtBefore(EmailJobStatus.PENDING,
                        now.minus(EMAIL_OVERDUE)),
                paymentOrders.countByStatusAndCreatedAtBefore(PaymentOrderStatus.CHECKOUT_READY,
                        now.minus(CHECKOUT_OLD)),
                paymentOrders.countByStatusAndVerifiedAtBefore(PaymentOrderStatus.VERIFIED,
                        now.minus(VERIFIED_STALE)),
                Math.max(0, requested - completed));
    }

    public PlatformSandboxPaymentSummary sandboxPaymentSummary(String actorUserId,
                                                               Instant from, Instant to,
                                                               PaymentProvider provider) {
        if (!platform.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
        Instant now = clock.instant();
        Instant defaultFrom = now.minus(Duration.ofDays(30));
        Instant defaultTo = now;
        PaymentOrderStatus applied = PaymentOrderStatus.APPLIED;

        Instant effectiveFrom = from != null ? from : defaultFrom;
        Instant effectiveTo = to != null ? to : defaultTo;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "From date must be before to date");
        }

        // Overall totals
        long totalOrders = paymentOrders.countByStatusAndCreatedAtBetween(applied, effectiveFrom, effectiveTo);
        BigDecimal totalAmount = paymentOrders.sumAmountBdtByStatusAndCreatedAtBetween(applied, effectiveFrom, effectiveTo);
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;

        // By provider breakdown
        List<PlatformSandboxPaymentSummary.ProviderBreakdown> byProvider = new ArrayList<>();
        if (provider != null) {
            long count = paymentOrders.countByStatusAndProviderAndCreatedAtBetween(applied, provider, effectiveFrom, effectiveTo);
            BigDecimal sum = paymentOrders.sumAmountBdtByStatusAndProviderAndCreatedAtBetween(applied, provider, effectiveFrom, effectiveTo);
            if (sum == null) sum = BigDecimal.ZERO;
            byProvider.add(new PlatformSandboxPaymentSummary.ProviderBreakdown(provider, count, sum));
        } else {
            for (PaymentProvider p : PaymentProvider.values()) {
                long count = paymentOrders.countByStatusAndProviderAndCreatedAtBetween(applied, p, effectiveFrom, effectiveTo);
                if (count > 0) {
                    BigDecimal sum = paymentOrders.sumAmountBdtByStatusAndProviderAndCreatedAtBetween(applied, p, effectiveFrom, effectiveTo);
                    if (sum == null) sum = BigDecimal.ZERO;
                    byProvider.add(new PlatformSandboxPaymentSummary.ProviderBreakdown(p, count, sum));
                }
            }
        }

        // By date range breakdown (weekly buckets)
        List<PlatformSandboxPaymentSummary.DateRangeBreakdown> byDateRange = new ArrayList<>();
        Instant bucketFrom = effectiveFrom;
        while (bucketFrom.isBefore(effectiveTo)) {
            Instant bucketTo = bucketFrom.plus(Duration.ofDays(7));
            if (bucketTo.isAfter(effectiveTo)) bucketTo = effectiveTo;
            long count = paymentOrders.countByStatusAndCreatedAtBetween(applied, bucketFrom, bucketTo);
            if (count > 0) {
                BigDecimal sum = paymentOrders.sumAmountBdtByStatusAndCreatedAtBetween(applied, bucketFrom, bucketTo);
                if (sum == null) sum = BigDecimal.ZERO;
                byDateRange.add(new PlatformSandboxPaymentSummary.DateRangeBreakdown(
                        bucketFrom.toString().substring(0, 10) + " to " + bucketTo.toString().substring(0, 10),
                        bucketFrom, bucketTo, count, sum));
            }
            bucketFrom = bucketTo;
        }

        return new PlatformSandboxPaymentSummary(now, totalOrders, totalAmount,
                "Sandbox activity (not live revenue)", byProvider, byDateRange);
    }
}
