package com.jachai.messaging.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("academy_conversations")
@CompoundIndexes({
        @CompoundIndex(name = "conversation_org_pair_unique",
                def = "{'organizationId': 1, 'instructorUserId': 1, 'learnerUserId': 1}", unique = true),
        @CompoundIndex(name = "conversation_org_instructor_created",
                def = "{'organizationId': 1, 'instructorUserId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "conversation_org_learner_created",
                def = "{'organizationId': 1, 'learnerUserId': 1, 'createdAt': -1}")
})
public record Conversation(@Id String id, String organizationId, String instructorUserId,
                           String learnerUserId, Instant createdAt) { }
