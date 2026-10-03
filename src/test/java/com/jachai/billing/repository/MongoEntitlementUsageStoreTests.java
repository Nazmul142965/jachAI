package com.jachai.billing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jachai.billing.model.UsageBucket;
import com.jachai.billing.model.UsageMetric;
import com.jachai.shared.error.ApiException;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class MongoEntitlementUsageStoreTests {
    @Mock ObjectProvider<MongoTemplate> provider;
    @Mock MongoTemplate mongo;

    private MongoEntitlementUsageStore store() {
        when(provider.getIfAvailable()).thenReturn(mongo);
        return new MongoEntitlementUsageStore(provider);
    }

    private static UsageBucket bucket(String organizationId, long used) {
        return new UsageBucket(organizationId + ":INVITATION:2026-10", organizationId,
                UsageMetric.INVITATION, "2026-10", used, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2027-12-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    void bucketReadIsLimitedToTheTenantAndTheRequestedIds() {
        when(mongo.find(any(Query.class), eq(UsageBucket.class))).thenReturn(List.of(bucket("org-a", 3)));

        Map<String, UsageBucket> found = store().buckets("org-a", List.of("org-a:INVITATION:2026-10"));

        assertEquals(3, found.get("org-a:INVITATION:2026-10").used());
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(UsageBucket.class));
        Document filter = query.getValue().getQueryObject();
        assertEquals("org-a", filter.get("organizationId"));
        assertEquals(List.of("org-a:INVITATION:2026-10"),
                ((Document) filter.get("_id")).get("$in"));
    }

    @Test
    void bucketReadWithoutIdsNeverTouchesMongo() {
        assertTrue(new MongoEntitlementUsageStore(provider).buckets("org-a", List.of()).isEmpty());
        verifyNoInteractions(mongo);
    }

    @Test
    void usageWriteOnlyReplacesTheObservedCounterOfThatTenant() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(UsageBucket.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        boolean replaced = store().replaceUsage("org-a", "org-a:INVITATION:2026-10", 5, 3,
                7L, Instant.parse("2026-10-20T12:00:00Z"), "admin-a");

        assertTrue(replaced);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(UsageBucket.class));
        Document filter = query.getValue().getQueryObject();
        assertEquals("org-a:INVITATION:2026-10", filter.get("_id"));
        assertEquals("org-a", filter.get("organizationId"));
        assertEquals(5L, filter.get("used"));
        assertEquals(7L, filter.get("revision"));
        assertFalse(filter.containsKey("lastChangedAt"),
                "BSON Date has millisecond precision and cannot serve as an ABA guard");
        Document changes = update.getValue().getUpdateObject();
        assertEquals(3L, changes.get("$set") instanceof Document set ? set.get("used") : null);
        assertEquals("admin-a", changes.get("$set") instanceof Document set ? set.get("reconciledByUserId") : null);
        assertTrue(changes.get("$set") instanceof Document set && set.containsKey("lastReconciledAt"));
        assertEquals(1L, ((Document) changes.get("$inc")).get("revision"));
    }

    @Test
    void usageWriteReportsAMissedCompareAndSet() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(UsageBucket.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        assertFalse(store().replaceUsage("org-a", "org-a:INVITATION:2026-10", 5, 3,
                7L, Instant.parse("2026-10-20T12:00:00Z"), "admin-a"));
    }

    @Test
    void legacyBucketWithoutRevisionUsesNullCasThenInitializesRevision() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(UsageBucket.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        assertTrue(store().replaceUsage("org-a", "org-a:CERTIFICATE:2026-10", 3, 2,
                null, Instant.parse("2026-10-20T12:00:00Z"), "admin-a"));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(UsageBucket.class));
        assertTrue(query.getValue().getQueryObject().containsKey("revision"));
        assertEquals(null, query.getValue().getQueryObject().get("revision"));
        assertEquals(1L, ((Document) update.getValue().getUpdateObject().get("$set")).get("revision"));
        assertFalse(update.getValue().getUpdateObject().containsKey("$inc"));
    }

    @Test
    void insertingABucketNeverOverwritesOneThatAlreadyExists() {
        when(mongo.insert(any(UsageBucket.class))).thenThrow(new DuplicateKeyException("exists"));

        assertFalse(store().insertBucket(bucket("org-a", 1)));
    }

    @Test
    void monthlyCountsGroupByUtcMonthInsideTheTenant() {
        when(mongo.aggregate(any(Aggregation.class), eq("assignments"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(
                        new Document("_id", "2026-09").append("count", 4),
                        new Document("_id", "2026-10").append("count", 2)), new Document()));

        Map<String, Long> counts = store().byUtcMonth("assignments", "org-a", "createdAt",
                Instant.parse("2026-08-01T00:00:00Z"), new Document("campaignId", null));

        assertEquals(Map.of("2026-09", 4L, "2026-10", 2L), counts);
        ArgumentCaptor<Aggregation> pipeline = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(pipeline.capture(), eq("assignments"), eq(Document.class));
        var stages = pipeline.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
        Document match = stages.getFirst().get("$match", Document.class);
        assertEquals("org-a", match.get("organizationId"));
        assertEquals(Date.from(Instant.parse("2026-08-01T00:00:00Z")),
                ((Document) match.get("createdAt")).get("$gte"));
        assertTrue(match.containsKey("campaignId"));
        String grouping = stages.get(1).toJson();
        assertTrue(grouping.contains("%Y-%m"));
        assertTrue(grouping.contains("UTC"));
    }

    @Test
    void undatedRecordsAreCountedSoTheyCannotSilentlyStrandAUnit() {
        when(mongo.aggregate(any(Aggregation.class), eq("certificates"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(
                        List.of(new Document("count", 2)), new Document()));

        assertEquals(2, store().undated("certificates", "org-a", "issuedAt"));
        ArgumentCaptor<Aggregation> pipeline = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo).aggregate(pipeline.capture(), eq("certificates"), eq(Document.class));
        Document match = pipeline.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT)
                .getFirst().get("$match", Document.class);
        assertEquals("org-a", match.get("organizationId"));
        assertTrue(match.containsKey("issuedAt"));
        assertEquals(null, match.get("issuedAt"));
    }

    @Test
    void totalAndDistinctCountOnlyTheTenant() {
        when(mongo.aggregate(any(Aggregation.class), eq("assessment_versions"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(new Document("count", 7)), new Document()));

        assertEquals(7, store().total("assessment_versions", "org-a"));
        assertEquals(7, store().distinct("assessment_versions", "org-a", "assessmentId"));
        ArgumentCaptor<Aggregation> pipelines = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongo, org.mockito.Mockito.times(2)).aggregate(pipelines.capture(), eq("assessment_versions"),
                eq(Document.class));
        assertEquals("org-a", pipelines.getAllValues().get(1).toPipeline(Aggregation.DEFAULT_CONTEXT)
                .getFirst().get("$match", Document.class).getString("organizationId"));
        assertTrue(pipelines.getAllValues().get(1).toPipeline(Aggregation.DEFAULT_CONTEXT)
                .get(1).toJson().contains("$assessmentId"));
    }

    @Test
    void emptyAggregateIsZero() {
        when(mongo.aggregate(any(Aggregation.class), eq("assessment_versions"), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(), new Document()));

        assertEquals(0, store().total("assessment_versions", "org-a"));
    }

    @Test
    void missingMongoTemplateIsReportedAsUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);

        ApiException failure = assertThrows(ApiException.class,
                () -> new MongoEntitlementUsageStore(provider).total("certificates", "org-a"));

        assertEquals("USAGE_STORE_UNAVAILABLE", failure.code());
    }
}
