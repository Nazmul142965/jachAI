package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PlatformUsageRepairStage;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.PlatformUsageRepairEventRepository;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.repository.EmailJobRepository;
import com.jachai.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformOperationsServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    @Mock PlatformAdminService platform;
    @Mock EmailJobRepository emailJobs;
    @Mock PaymentOrderRepository paymentOrders;
    @Mock PlatformUsageRepairEventRepository repairEvents;

    private PlatformOperationsService service() {
        return new PlatformOperationsService(platform, emailJobs, paymentOrders,
                repairEvents, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ordinaryUserCannotReadGlobalOperationalCounts() {
        assertEquals("PLATFORM_ADMIN_REQUIRED", assertThrows(ApiException.class,
                () -> service().summary("ordinary")).code());
        verifyNoInteractions(emailJobs, paymentOrders, repairEvents);
    }

    @Test
    void summaryCountsOnlyOverdueOrUnresolvedStatesWithoutTenantContent() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(emailJobs.countByStatus(EmailJobStatus.FAILED)).thenReturn(2L);
        when(emailJobs.countByStatusAndNextAttemptAtBefore(EmailJobStatus.PENDING,
                NOW.minusSeconds(15 * 60))).thenReturn(3L);
        when(paymentOrders.countByStatusAndCreatedAtBefore(PaymentOrderStatus.CHECKOUT_READY,
                NOW.minusSeconds(24 * 60 * 60))).thenReturn(4L);
        when(paymentOrders.countByStatusAndVerifiedAtBefore(PaymentOrderStatus.VERIFIED,
                NOW.minusSeconds(5 * 60))).thenReturn(5L);
        when(repairEvents.countByStage(PlatformUsageRepairStage.REQUESTED)).thenReturn(7L);
        when(repairEvents.countByStage(PlatformUsageRepairStage.COMPLETED)).thenReturn(6L);

        var view = service().summary("admin");

        assertEquals(NOW, view.generatedAt());
        assertEquals(2L, view.failedEmailJobs());
        assertEquals(3L, view.overdueEmailJobs());
        assertEquals(4L, view.oldOpenSandboxOrders());
        assertEquals(5L, view.verifiedButUnappliedOrders());
        assertEquals(1L, view.usageRepairRunsWithoutCompletion());
        assertEquals(6, view.getClass().getRecordComponents().length);
        verify(platform).isPlatformAdmin("admin");
    }

    @Test
    void incompleteAuditCountNeverBecomesNegative() {
        when(platform.isPlatformAdmin("admin")).thenReturn(true);
        when(repairEvents.countByStage(PlatformUsageRepairStage.REQUESTED)).thenReturn(2L);
        when(repairEvents.countByStage(PlatformUsageRepairStage.COMPLETED)).thenReturn(3L);

        assertEquals(0L, service().summary("admin").usageRepairRunsWithoutCompletion());
    }
}
