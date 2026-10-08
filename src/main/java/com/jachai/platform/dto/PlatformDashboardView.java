package com.jachai.platform.dto;

import com.jachai.billing.model.PaymentProvider;
import com.jachai.support.type.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Platform-wide operational dashboard. Sandbox amounts are never live revenue.
 */
public record PlatformDashboardView(
        Instant generatedAt,
        // Sandbox payment summary (separate from live revenue)
        long sandboxAppliedOrders,
        BigDecimal sandboxAppliedAmountBdt,
        List<ProviderBreakdown> sandboxByProvider,
        List<MonthlySandboxOrders> monthlySandboxOrders,
        // Active workspaces
        long activeTeamWorkspaces,
        // Support
        long openSupportTickets,
        long supportTicketsWorking,
        long supportTicketsWaiting,
        // Email & payment alerts
        long failedEmailJobs,
        long overdueEmailJobs,
        long paymentsAwaitingVerification,
        long oldOpenSandboxOrders,
        // Recent activity
        List<RecentSandboxPayment> recentSandboxPayments,
        List<RecentSupportTicket> recentSupportTickets) {

    public record ProviderBreakdown(PaymentProvider provider, long count, BigDecimal sumBdt) { }

    public record MonthlySandboxOrders(String month, long orders) { }

    public record RecentSandboxPayment(String id, String organizationId, PaymentProvider provider,
                                       BigDecimal amountBdt, Instant appliedAt) { }

    public record RecentSupportTicket(String id, String referenceNumber, String organizationId,
                                      String subject, TicketStatus status, Instant updatedAt) { }
}