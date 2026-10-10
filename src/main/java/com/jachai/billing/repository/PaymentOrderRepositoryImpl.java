package com.jachai.billing.repository;

import com.jachai.billing.model.PaymentOrder;
import com.jachai.billing.model.PaymentOrderStatus;
import com.jachai.billing.model.PaymentProvider;
import java.math.BigDecimal;
import java.time.Instant;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;

class PaymentOrderRepositoryImpl implements PaymentOrderRepositoryCustom {

    private final MongoTemplate mongo;

    PaymentOrderRepositoryImpl(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public BigDecimal sumAmountBdtByStatusAndCreatedAtBetween(
            PaymentOrderStatus status, Instant from, Instant to) {
        return sum(Criteria.where("status").is(status)
                .and("createdAt").gte(from).lte(to));
    }

    @Override
    public BigDecimal sumAmountBdtByStatusAndProviderAndCreatedAtBetween(
            PaymentOrderStatus status, PaymentProvider provider, Instant from, Instant to) {
        return sum(Criteria.where("status").is(status)
                .and("provider").is(provider)
                .and("createdAt").gte(from).lte(to));
    }

    private BigDecimal sum(Criteria criteria) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(criteria),
                Aggregation.group().sum("amountBdt").as("total"));
        Document result = mongo.aggregate(aggregation, PaymentOrder.class, Document.class)
                .getUniqueMappedResult();
        if (result == null) return BigDecimal.ZERO;

        Object total = result.get("total");
        if (total instanceof Decimal128 decimal) return decimal.bigDecimalValue();
        if (total instanceof BigDecimal decimal) return decimal;
        if (total instanceof Number number) return new BigDecimal(number.toString());
        return BigDecimal.ZERO;
    }
}
