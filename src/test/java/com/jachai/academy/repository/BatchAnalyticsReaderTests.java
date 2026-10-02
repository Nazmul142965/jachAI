package com.jachai.academy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

@ExtendWith(MockitoExtension.class)
class BatchAnalyticsReaderTests {
    @Mock MongoTemplate mongo;

    @Test
    void aggregationScopesAssignmentsAndLatestSubmittedAttemptToTenant() {
        Document row = new Document("assigned", 5).append("submitted", 3)
                .append("passed", 2).append("average", 75.0);
        when(mongo.aggregate(any(Aggregation.class), eq("assignments"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(row), new Document()));

        var result = new BatchAnalyticsReader(mongo).totals("org-a", "ba-a");

        assertEquals(5, result.assigned());
        assertEquals(3, result.submitted());
        assertEquals(2, result.passed());
        assertEquals(75.0, result.average());
        ArgumentCaptor<Aggregation> capture = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(capture.capture(), eq("assignments"), eq(Document.class));
        var pipeline = capture.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
        assertEquals("org-a", pipeline.getFirst().get("$match", Document.class).getString("organizationId"));
        assertEquals("ba-a", pipeline.getFirst().get("$match", Document.class).getString("batchAssessmentId"));
        assertTrue(pipeline.get(1).toJson().contains("$$organizationId"));
        assertTrue(pipeline.get(1).toJson().contains("SUBMITTED"));
    }

    @Test
    void emptyCohortHasZeroTotalsAndNoAverage() {
        when(mongo.aggregate(any(Aggregation.class), eq("assignments"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(), new Document()));

        var result = new BatchAnalyticsReader(mongo).totals("org-a", "ba-a");

        assertEquals(0, result.assigned());
        assertNull(result.average());
    }
}
