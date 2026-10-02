package com.jachai.billing.dto;

import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentOrderView(String id, PaymentProvider provider, PaymentOrderStatus status,
                               BigDecimal amountBdt, Instant createdAt, Instant verifiedAt,
                               Instant appliedAt) {
    public static PaymentOrderView from(PaymentOrder order) {
        return new PaymentOrderView(order.id(), order.provider(), order.status(),
                order.amountBdt(), order.createdAt(), order.verifiedAt(), order.appliedAt());
    }
}
