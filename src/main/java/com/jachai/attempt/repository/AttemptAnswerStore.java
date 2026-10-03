package com.jachai.attempt.repository;

import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AttemptAnswerStore {
    private final MongoTemplate mongo;

    public boolean saveOptions(Attempt attempt, String questionId, Set<String> selected) {
        return update(attempt, new Update().set("answers", attempt.withAnswer(questionId, selected).answers())
                .inc("revision", 1L));
    }

    public boolean saveText(Attempt attempt, String questionId, String text) {
        return update(attempt, new Update().set("textAnswers", attempt.withTextAnswer(questionId, text).textAnswers())
                .inc("revision", 1L));
    }

    private boolean update(Attempt attempt, Update change) {
        // Evaluate the deadline on MongoDB at mutation time, not against an earlier Java clock read.
        Document query = new Document("_id", attempt.id())
                .append("organizationId", attempt.organizationId())
                .append("userId", attempt.userId())
                .append("status", AttemptStatus.IN_PROGRESS.name())
                .append("revision", attempt.revision())
                .append("$expr", new Document("$gt", List.of("$deadline", "$$NOW")));
        return mongo.updateFirst(new BasicQuery(query), change, Attempt.class).getModifiedCount() == 1;
    }
}
