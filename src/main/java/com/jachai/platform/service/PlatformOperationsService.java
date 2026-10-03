package com.jachai.platform.service;

import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PlatformUsageRepairStage;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.PlatformUsageRepairEventRepository;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.repository.EmailJobRepository;
import com.jachai.platform.dto.PlatformOperationsView;
import com.jachai.shared.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
}
