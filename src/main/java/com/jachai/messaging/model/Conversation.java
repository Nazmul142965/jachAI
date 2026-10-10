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
                           String learnerUserId, Instant createdAt,
                           /** When each side last opened the thread. Null until they first do. */
                           Instant instructorLastReadAt, Instant learnerLastReadAt,
                           /** Denormalised from the newest message, so the list can sort and preview without reading messages. */
                           Instant lastMessageAt, String lastMessagePreview) {
    public Conversation(String id, String organizationId, String instructorUserId,
                        String learnerUserId, Instant createdAt) {
        this(id, organizationId, instructorUserId, learnerUserId, createdAt, null, null, null, null);
    }
}
