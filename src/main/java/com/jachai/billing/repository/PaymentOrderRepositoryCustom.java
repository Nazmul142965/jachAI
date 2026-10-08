package com.jachai.billing.repository;

import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;

public interface PaymentOrderRepositoryCustom {

    BigDecimal sumAmountBdtByStatusAndCreatedAtBetween(PaymentOrderStatus status, Instant from, Instant to);

    BigDecimal sumAmountBdtByStatusAndProviderAndCreatedAtBetween(
            PaymentOrderStatus status, PaymentProvider provider, Instant from, Instant to);
}
