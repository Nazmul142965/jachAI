package com.jachai.platform.dto;

import com.jachai.billing.model.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PlatformSandboxPaymentSummary(Instant generatedAt, long totalOrders,
                                            BigDecimal totalAmountBdt, String label,
                                            List<ProviderBreakdown> byProvider,
                                            List<DateRangeBreakdown> byDateRange) {

    public record ProviderBreakdown(PaymentProvider provider, long count, BigDecimal sumBdt) { }

    public record DateRangeBreakdown(String rangeLabel, Instant from, Instant to, long count, BigDecimal sumBdt) { }
}