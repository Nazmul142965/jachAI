package com.jachai.platform.service;

import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.repository.*;
import com.jachai.mail.repository.EmailJobRepository;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformSandboxPaymentReportTests {
    private final PlatformAdminService admins = mock(PlatformAdminService.class);
    private final PaymentOrderRepository orders = mock(PaymentOrderRepository.class);
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");
    private final PlatformOperationsService service = new PlatformOperationsService(admins, mock(EmailJobRepository.class), orders,
            mock(PlatformUsageRepairEventRepository.class), Clock.fixed(now, ZoneOffset.UTC));

    @Test void providerFilterAppliesToTotalsAndEveryWeeklyBucket() {
        when(admins.isPlatformAdmin("admin")).thenReturn(true);
        when(orders.countByStatusAndProviderAndCreatedAtBetween(any(), eq(PaymentProvider.BKASH), any(), any())).thenReturn(2L);
        when(orders.sumAmountBdtByStatusAndProviderAndCreatedAtBetween(any(), eq(PaymentProvider.BKASH), any(), any())).thenReturn(new BigDecimal("500"));
        var report = service.sandboxPaymentSummary("admin", now.minusSeconds(86400), now, PaymentProvider.BKASH);
        assertThat(report.totalOrders()).isEqualTo(2);
        assertThat(report.totalAmountBdt()).isEqualByComparingTo("500");
        assertThat(report.byDateRange()).hasSize(1);
        assertThat(report.byDateRange().getFirst().sumBdt()).isEqualByComparingTo("500");
        verify(orders, never()).countByStatusAndCreatedAtBetween(any(), any(), any());
        verify(orders, never()).sumAmountBdtByStatusAndCreatedAtBetween(any(), any(), any());
    }

    @Test void overlyLargeRangesAreRejectedBeforeRunningDatabaseAggregates() {
        when(admins.isPlatformAdmin("admin")).thenReturn(true);
        assertThatThrownBy(() -> service.sandboxPaymentSummary("admin", now.minus(Duration.ofDays(367)), now, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("366 days");
        verifyNoInteractions(orders);
    }
}
