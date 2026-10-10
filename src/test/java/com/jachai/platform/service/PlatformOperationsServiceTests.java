package com.jachai.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.PlatformUsageRepairEventRepository;
import com.jachai.mail.repository.EmailJobRepository;
import com.jachai.platform.dto.PlatformOperationsView;
import com.jachai.platform.dto.PlatformSandboxPaymentSummary;
import com.jachai.platform.service.PlatformAdminService;
import com.jachai.shared.error.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PlatformOperationsServiceTests {

    private PlatformAdminService platform;
    private EmailJobRepository emailJobs;
    private PaymentOrderRepository paymentOrders;
    private PlatformUsageRepairEventRepository repairEvents;
    private PlatformOperationsService service;

    @BeforeEach
    void setUp() {
        platform = mock(PlatformAdminService.class);
        emailJobs = mock(EmailJobRepository.class);
        paymentOrders = mock(PaymentOrderRepository.class);
        repairEvents = mock(PlatformUsageRepairEventRepository.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), java.time.ZoneOffset.UTC);
        service = new PlatformOperationsService(platform, emailJobs, paymentOrders, repairEvents, fixedClock);
    }

    @Test
    void summaryThrowsWhenNotPlatformAdmin() {
        when(platform.isPlatformAdmin("user1")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.summary("user1"));
        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("PLATFORM_ADMIN_REQUIRED", ex.code());
    }

    @Test
    void sandboxPaymentSummaryThrowsWhenNotPlatformAdmin() {
        when(platform.isPlatformAdmin("user1")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.sandboxPaymentSummary("user1", null, null, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("PLATFORM_ADMIN_REQUIRED", ex.code());
    }

    @Test
    void sandboxPaymentSummaryThrowsWhenFromAfterTo() {
        when(platform.isPlatformAdmin("admin1")).thenReturn(true);
        Instant from = Instant.parse("2026-10-08T12:00:00Z");
        Instant to = Instant.parse("2026-10-07T12:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.sandboxPaymentSummary("admin1", from, to, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_DATE_RANGE", ex.code());
    }

    @Test
    void sandboxPaymentSummaryDefaultsToLast30Days() {
        when(platform.isPlatformAdmin("admin1")).thenReturn(true);
        when(paymentOrders.countByStatusAndCreatedAtBetween(any(), any(), any())).thenReturn(5L);
        when(paymentOrders.sumAmountBdtByStatusAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(new BigDecimal("5000.00"));
        when(paymentOrders.countByStatusAndProviderAndCreatedAtBetween(any(), any(), any(), any())).thenReturn(0L);
        when(paymentOrders.sumAmountBdtByStatusAndProviderAndCreatedAtBetween(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        PlatformSandboxPaymentSummary result = service.sandboxPaymentSummary("admin1", null, null, null);

        assertEquals(5L, result.totalOrders());
        assertEquals(new BigDecimal("5000.00"), result.totalAmountBdt());
        assertEquals("Sandbox activity (not live revenue)", result.label());
    }

    @Test
    void sandboxPaymentSummaryFiltersByProvider() {
        when(platform.isPlatformAdmin("admin1")).thenReturn(true);
        when(paymentOrders.countByStatusAndCreatedAtBetween(any(), any(), any())).thenReturn(10L);
        when(paymentOrders.sumAmountBdtByStatusAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(new BigDecimal("10000.00"));
        when(paymentOrders.countByStatusAndProviderAndCreatedAtBetween(any(), any(), any(), any()))
                .thenReturn(3L);
        when(paymentOrders.sumAmountBdtByStatusAndProviderAndCreatedAtBetween(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("3000.00"));

        PlatformSandboxPaymentSummary result = service.sandboxPaymentSummary("admin1", null, null, PaymentProvider.BKASH);

        assertEquals(10L, result.totalOrders());
        assertEquals(1, result.byProvider().size());
        assertEquals(PaymentProvider.BKASH, result.byProvider().get(0).provider());
        assertEquals(3L, result.byProvider().get(0).count());
        assertEquals(new BigDecimal("3000.00"), result.byProvider().get(0).sumBdt());
    }

    @Test
    void sandboxPaymentSummaryIncludesDateRangeBreakdown() {
        when(platform.isPlatformAdmin("admin1")).thenReturn(true);
        when(paymentOrders.countByStatusAndCreatedAtBetween(any(), any(), any())).thenReturn(2L);
        when(paymentOrders.sumAmountBdtByStatusAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(new BigDecimal("2000.00"));
        when(paymentOrders.countByStatusAndProviderAndCreatedAtBetween(any(), any(), any(), any())).thenReturn(0L);
        when(paymentOrders.sumAmountBdtByStatusAndProviderAndCreatedAtBetween(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        PlatformSandboxPaymentSummary result = service.sandboxPaymentSummary("admin1", null, null, null);

        assertEquals(2L, result.totalOrders());
        // With default 30-day range and fixed clock at 2026-10-08, there are ~5 weekly buckets
        // but only those with count > 0 are included. Mock returns 2L for all, so we get 5 buckets.
        assertEquals(5, result.byDateRange().size());
    }
}