package com.jachai.platform.service;

import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import com.jachai.billing.model.PlanTier;
import com.jachai.billing.model.SubscriptionStatus;
import com.jachai.billing.repository.PaymentOrderRepository;
import com.jachai.billing.repository.SubscriptionRepository;
import com.jachai.mail.model.EmailJobStatus;
import com.jachai.mail.repository.EmailJobRepository;
import com.jachai.platform.dto.PlatformDashboardView;
import com.jachai.shared.error.ApiException;
import com.jachai.support.repository.SupportTicketRepository;
import com.jachai.support.type.TicketStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlatformDashboardService {

    private static final List<TicketStatus> OPEN_STATUSES = List.of(
            TicketStatus.RECEIVED, TicketStatus.WORKING_ON_IT, TicketStatus.WAITING_FOR_YOUR_REPLY);

    private final PlatformAdminService admins;
    private final PaymentOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final SupportTicketRepository tickets;
    private final EmailJobRepository emailJobs;
    private final MongoTemplate mongo;
    private final Clock clock;

    @Autowired
    public PlatformDashboardService(PlatformAdminService admins, PaymentOrderRepository orders,
                                    SubscriptionRepository subscriptions, SupportTicketRepository tickets,
                                    EmailJobRepository emailJobs, MongoTemplate mongo) {
        this(admins, orders, subscriptions, tickets, emailJobs, mongo, Clock.systemUTC());
    }

    PlatformDashboardService(PlatformAdminService admins, PaymentOrderRepository orders,
                             SubscriptionRepository subscriptions, SupportTicketRepository tickets,
                             EmailJobRepository emailJobs, MongoTemplate mongo, Clock clock) {
        this.admins = admins;
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.tickets = tickets;
        this.emailJobs = emailJobs;
        this.mongo = mongo;
        this.clock = clock;
    }

    public PlatformDashboardView summary(String actorUserId) {
        if (!admins.isPlatformAdmin(actorUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator access required");
        }
        Instant now = clock.instant();

        // Sandbox payment summary (separate from live revenue)
        Aggregation totalQuery = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").is(PaymentOrderStatus.APPLIED)),
                Aggregation.group().count().as("orders").sum("amountBdt").as("amountBdt"));
        Document total = mongo.aggregate(totalQuery, PaymentOrder.class, Document.class).getUniqueMappedResult();
        long appliedCount = total == null ? 0 : ((Number) total.get("orders")).longValue();
        BigDecimal appliedAmount = total == null ? BigDecimal.ZERO : decimal(total.get("amountBdt"));

        // By provider breakdown
        List<PlatformDashboardView.ProviderBreakdown> byProvider = new ArrayList<>();
        for (PaymentProvider p : PaymentProvider.values()) {
            long count = mongo.count(Query.query(Criteria.where("status").is(PaymentOrderStatus.APPLIED)
                    .and("provider").is(p)), PaymentOrder.class);
            if (count > 0) {
                Aggregation sumQuery = Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("status").is(PaymentOrderStatus.APPLIED)
                                .and("provider").is(p)),
                        Aggregation.group().sum("amountBdt").as("sum"));
                Document sum = mongo.aggregate(sumQuery, PaymentOrder.class, Document.class).getUniqueMappedResult();
                BigDecimal sumBdt = sum == null ? BigDecimal.ZERO : decimal(sum.get("sum"));
                byProvider.add(new PlatformDashboardView.ProviderBreakdown(p, count, sumBdt));
            }
        }

        // Monthly sandbox orders (last 6 months)
        YearMonth current = YearMonth.from(now.atZone(ZoneOffset.UTC));
        List<PlatformDashboardView.MonthlySandboxOrders> months = new ArrayList<>();
        for (int offset = 5; offset >= 0; offset--) {
            YearMonth month = current.minusMonths(offset);
            Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            long count = mongo.count(Query.query(Criteria.where("status").is(PaymentOrderStatus.APPLIED)
                    .and("appliedAt").gte(from).lt(to)), PaymentOrder.class);
            months.add(new PlatformDashboardView.MonthlySandboxOrders(month.toString(), count));
        }

        // Active TEAM workspaces
        long activeTeamWorkspaces = subscriptions.countByTierAndStatusAndCurrentPeriodEndAfter(
                PlanTier.TEAM, SubscriptionStatus.ACTIVE, now);

        // Support tickets
        long openTickets = tickets.countByStatusIn(OPEN_STATUSES);
        long workingTickets = tickets.countByStatus(TicketStatus.WORKING_ON_IT);
        long waitingTickets = tickets.countByStatus(TicketStatus.WAITING_FOR_YOUR_REPLY);

        // Email & payment alerts
        long failedEmailJobs = emailJobs.countByStatus(EmailJobStatus.FAILED);
        long overdueEmailJobs = emailJobs.countByStatusAndNextAttemptAtBefore(EmailJobStatus.PENDING,
                now.minus(Duration.ofMinutes(15)));
        long paymentsAwaitingVerification = orders.countByStatusAndCreatedAtBefore(PaymentOrderStatus.VERIFIED,
                now.minus(Duration.ofMinutes(5)));
        long oldOpenSandboxOrders = orders.countByStatusAndCreatedAtBefore(PaymentOrderStatus.CHECKOUT_READY,
                now.minus(Duration.ofDays(1)));

        // Recent sandbox payments (last 5)
        var recentPayments = orders.findByStatus(PaymentOrderStatus.APPLIED,
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "appliedAt"))).getContent().stream()
                .map(order -> new PlatformDashboardView.RecentSandboxPayment(order.id(), order.organizationId(),
                        order.provider(), order.amountBdt(), order.appliedAt())).toList();

        // Recent support tickets (last 5, not resolved/closed)
        var recentTickets = tickets.findByStatusNotIn(List.of(TicketStatus.RESOLVED, TicketStatus.CLOSED),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "updatedAt"))).getContent().stream()
                .map(ticket -> new PlatformDashboardView.RecentSupportTicket(ticket.getId(), ticket.getReferenceNumber(),
                        ticket.getOrganizationId(), ticket.getSubject(), ticket.getStatus(), ticket.getUpdatedAt())).toList();

        return new PlatformDashboardView(now, appliedCount, appliedAmount, byProvider, months,
                activeTeamWorkspaces, openTickets, workingTickets, waitingTickets,
                failedEmailJobs, overdueEmailJobs, paymentsAwaitingVerification, oldOpenSandboxOrders,
                recentPayments, recentTickets);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Decimal128 decimal) return decimal.bigDecimalValue();
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return BigDecimal.ZERO;
    }
}