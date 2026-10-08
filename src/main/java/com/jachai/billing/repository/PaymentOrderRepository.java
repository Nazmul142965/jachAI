package com.jachai.billing.repository;

import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface PaymentOrderRepository extends MongoRepository<PaymentOrder, String> {
    Page<PaymentOrder> findByOrganizationId(String organizationId, Pageable pageable);
    Page<PaymentOrder> findByStatus(PaymentOrderStatus status, Pageable pageable);
    Optional<PaymentOrder> findByIdAndOrganizationId(String id, String organizationId);
    long countByStatusAndCreatedAtBefore(PaymentOrderStatus status, Instant before);
    long countByStatusAndVerifiedAtBefore(PaymentOrderStatus status, Instant before);

    long countByStatusAndCreatedAtBetween(PaymentOrderStatus status, Instant from, Instant to);
    BigDecimal sumAmountBdtByStatusAndCreatedAtBetween(PaymentOrderStatus status, Instant from, Instant to);
    long countByStatusAndProviderAndCreatedAtBetween(PaymentOrderStatus status, PaymentProvider provider, Instant from, Instant to);
    BigDecimal sumAmountBdtByStatusAndProviderAndCreatedAtBetween(PaymentOrderStatus status, PaymentProvider provider, Instant from, Instant to);
}
