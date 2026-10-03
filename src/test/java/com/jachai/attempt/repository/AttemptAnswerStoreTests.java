package com.jachai.attempt.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class AttemptAnswerStoreTests {
    @Mock MongoTemplate mongo;

    private Attempt attempt() {
        return new Attempt("attempt", "org-a", "assignment", "version", "learner", 1,
                Instant.parse("2026-10-03T10:00:00Z"), Instant.parse("2026-10-03T10:30:00Z"),
                AttemptStatus.IN_PROGRESS, Map.of(), null, null, null, null, 3L);
    }

    @Test
    void optionWriteRequiresTenantOwnerRevisionAndDatabaseCurrentTime() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Attempt.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        assertTrue(new AttemptAnswerStore(mongo).saveOptions(attempt(), "q1", Set.of("a")));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq(Attempt.class));
        Document filter = query.getValue().getQueryObject();
        assertEquals("attempt", filter.getString("_id"));
        assertEquals("org-a", filter.getString("organizationId"));
        assertEquals("learner", filter.getString("userId"));
        assertEquals("IN_PROGRESS", filter.getString("status"));
        assertEquals(3L, filter.getLong("revision"));
        assertEquals(new Document("$gt", List.of("$deadline", "$$NOW")), filter.get("$expr"));
        Document write = update.getValue().getUpdateObject();
        assertEquals(1L, ((Document) write.get("$inc")).getLong("revision"));
        assertTrue(((Document) write.get("$set")).containsKey("answers"));
    }

    @Test
    void rejectedTextWriteReportsNoMutation() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Attempt.class)))
                .thenReturn(UpdateResult.acknowledged(0L, 0L, null));

        assertFalse(new AttemptAnswerStore(mongo).saveText(attempt(), "q-text", "answer"));
    }
}
