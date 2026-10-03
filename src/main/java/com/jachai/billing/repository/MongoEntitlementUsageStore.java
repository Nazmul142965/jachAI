package com.jachai.billing.repository;

import com.jachai.billing.model.UsageBucket;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MongoEntitlementUsageStore implements EntitlementUsageStore {
    private final ObjectProvider<MongoTemplate> mongoProvider;

    @Override
    public Map<String, UsageBucket> buckets(String organizationId, Collection<String> bucketIds) {
        if (bucketIds.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(bucketIds).and("organizationId").is(organizationId));
        Map<String, UsageBucket> found = new HashMap<>();
        for (UsageBucket bucket : template().find(query, UsageBucket.class)) {
            found.put(bucket.id(), bucket);
        }
        return found;
    }

    @Override
    public boolean insertBucket(UsageBucket bucket) {
        try {
            template().insert(bucket);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public boolean replaceUsage(String organizationId, String bucketId, long expectedUsage, long newUsage,
                                Long expectedRevision, Instant reconciledAt, String actorUserId) {
        Query query = Query.query(Criteria.where("_id").is(bucketId)
                .and("organizationId").is(organizationId)
                .and("used").is(expectedUsage)
                .and("revision").is(expectedRevision));
        Update update = new Update().set("used", newUsage)
                .set("lastReconciledAt", reconciledAt)
                .set("reconciledByUserId", actorUserId);
        // Legacy documents have no revision field; initialize them under the same CAS.
        if (expectedRevision == null) update.set("revision", 1L);
        else update.inc("revision", 1L);
        return template().updateFirst(query, update, UsageBucket.class).getMatchedCount() == 1;
    }

    @Override
    public long total(String collection, String organizationId) {
        return singleNumber(aggregate(collection, tenant(organizationId),
                context -> new Document("$count", "count")), "count");
    }

    @Override
    public long distinct(String collection, String organizationId, String field) {
        AggregationOperation group = context -> new Document("$group", new Document("_id", "$" + field));
        return singleNumber(aggregate(collection, tenant(organizationId), group,
                context -> new Document("$count", "count")), "count");
    }

    @Override
    public long undated(String collection, String organizationId, String dateField) {
        Document match = tenant(organizationId).append(dateField, null);
        return singleNumber(aggregate(collection, match,
                context -> new Document("$count", "count")), "count");
    }

    @Override
    public Map<String, Long> byUtcMonth(String collection, String organizationId, String dateField,
                                        Instant fromInclusive, Document extraMatch) {
        Document match = tenant(organizationId)
                .append(dateField, new Document("$gte", Date.from(fromInclusive)));
        if (extraMatch != null) {
            match.putAll(extraMatch);
        }
        Document month = new Document("$dateToString", new Document("format", "%Y-%m")
                .append("date", "$" + dateField)
                .append("timezone", "UTC"));
        AggregationOperation group = context -> new Document("$group",
                new Document("_id", month).append("count", new Document("$sum", 1)));
        Map<String, Long> counts = new HashMap<>();
        for (Document row : aggregate(collection, match, group)) {
            counts.put(row.getString("_id"), number(row, "count"));
        }
        return counts;
    }

    private Document tenant(String organizationId) {
        return new Document("organizationId", organizationId);
    }

    private List<Document> aggregate(String collection, Document match, AggregationOperation... stages) {
        AggregationOperation[] pipeline = new AggregationOperation[stages.length + 1];
        pipeline[0] = context -> new Document("$match", match);
        System.arraycopy(stages, 0, pipeline, 1, stages.length);
        return template().aggregate(Aggregation.newAggregation(pipeline), collection, Document.class)
                .getMappedResults();
    }

    private long singleNumber(List<Document> rows, String key) {
        return rows.isEmpty() ? 0 : number(rows.getFirst(), key);
    }

    private static long number(Document row, String key) {
        return row.get(key) instanceof Number value ? value.longValue() : 0;
    }

    private MongoTemplate template() {
        MongoTemplate mongo = mongoProvider.getIfAvailable();
        if (mongo == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "USAGE_STORE_UNAVAILABLE",
                    "Entitlement usage storage is unavailable");
        }
        return mongo;
    }
}
