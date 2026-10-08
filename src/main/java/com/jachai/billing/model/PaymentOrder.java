package com.jachai.billing.model;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("payment_orders")
@CompoundIndexes({
        @CompoundIndex(name = "payment_org_created", def = "{'organizationId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "payment_status_created", def = "{'status': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "payment_status_verified", def = "{'status': 1, 'verifiedAt': 1}")
})
public record PaymentOrder(
        @Id String id,
        String organizationId,
        String actorUserId,
        PaymentProvider provider,
        PaymentOrderStatus status,
        BigDecimal amountBdt,
        @Indexed(unique = true, sparse = true) String gatewaySessionId,
        String gatewayValidationId,
        @Indexed(unique = true, sparse = true) String gatewayTransactionId,
        Instant createdAt,
        Instant verifiedAt,
        Instant appliedAt,
        @Version Long revision) {

    public PaymentOrder checkoutReady(String sessionId) {
        return new PaymentOrder(id, organizationId, actorUserId, provider,
                PaymentOrderStatus.CHECKOUT_READY, amountBdt, sessionId, gatewayValidationId,
                gatewayTransactionId, createdAt, verifiedAt, appliedAt, revision);
    }

    public PaymentOrder verified(String validationId, String transactionId, Instant at) {
        return new PaymentOrder(id, organizationId, actorUserId, provider,
                PaymentOrderStatus.VERIFIED, amountBdt, gatewaySessionId, validationId,
                transactionId, createdAt, at, appliedAt, revision);
    }

    public PaymentOrder applied(Instant at) {
        return new PaymentOrder(id, organizationId, actorUserId, provider,
                PaymentOrderStatus.APPLIED, amountBdt, gatewaySessionId, gatewayValidationId,
                gatewayTransactionId, createdAt, verifiedAt, at, revision);
    }

    public PaymentOrder cancelled() {
        return new PaymentOrder(id, organizationId, actorUserId, provider,
                PaymentOrderStatus.CANCELLED, amountBdt, gatewaySessionId,
                gatewayValidationId, gatewayTransactionId, createdAt, verifiedAt,
                appliedAt, revision);
    }
}
