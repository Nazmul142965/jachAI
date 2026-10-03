package com.jachai.academy.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BatchAnalyticsReader {
    private final MongoTemplate mongo;

    public Totals totals(String organizationId, String batchAssessmentId) {
        AggregationOperation match = context -> new Document("$match",
                new Document("organizationId", organizationId)
                        .append("batchAssessmentId", batchAssessmentId));
        Document latestAttemptMatch = new Document("$expr", new Document("$and", List.of(
                new Document("$eq", List.of("$assignmentId", "$$assignmentId")),
                new Document("$eq", List.of("$organizationId", "$$organizationId")),
                new Document("$eq", List.of("$status", "SUBMITTED")))));
        AggregationOperation lookup = context -> new Document("$lookup", new Document("from", "attempts")
                .append("let", new Document("assignmentId", "$_id")
                        .append("organizationId", "$organizationId"))
                .append("pipeline", List.of(
                        new Document("$match", latestAttemptMatch),
                        new Document("$sort", new Document("attemptNumber", -1)),
                        new Document("$limit", 1),
                        new Document("$project", new Document("percentage", 1).append("passed", 1))))
                .append("as", "latestAttempt"));
        AggregationOperation unwind = context -> new Document("$unwind",
                new Document("path", "$latestAttempt")
                        .append("preserveNullAndEmptyArrays", true));
        AggregationOperation group = context -> new Document("$group", new Document("_id", null)
                .append("assigned", new Document("$sum", 1))
                .append("submitted", new Document("$sum", new Document("$cond", List.of(
                        new Document("$ifNull", List.of("$latestAttempt._id", false)), 1, 0))))
                .append("graded", new Document("$sum", new Document("$cond", List.of(
                        new Document("$gte", List.of(
                                new Document("$ifNull", List.of("$latestAttempt.percentage", -1)), 0)), 1, 0))))
                .append("passed", new Document("$sum", new Document("$cond", List.of(
                        new Document("$eq", List.of("$latestAttempt.passed", true)), 1, 0))))
                .append("average", new Document("$avg", "$latestAttempt.percentage")));
        Document result = mongo.aggregate(Aggregation.newAggregation(match, lookup, unwind, group),
                "assignments", Document.class).getUniqueMappedResult();
        if (result == null) return new Totals(0, 0, 0, 0, null);
        return new Totals(number(result, "assigned"), number(result, "submitted"),
                number(result, "graded"), number(result, "passed"), result.get("average") instanceof Number average
                        ? average.doubleValue() : null);
    }

    private static long number(Document source, String key) {
        return source.get(key) instanceof Number value ? value.longValue() : 0;
    }

    public record Totals(long assigned, long submitted, long graded, long passed, Double average) { }
}
