package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("academy_batches")
@CompoundIndexes({
        @CompoundIndex(name = "batch_org_course_key_unique",
                def = "{'organizationId': 1, 'courseId': 1, 'nameKey': 1}", unique = true),
        @CompoundIndex(name = "batch_org_course_created",
                def = "{'organizationId': 1, 'courseId': 1, 'createdAt': -1}")
})
public record Batch(@Id String id, String organizationId, String courseId, String name,
                    String nameKey, Instant startsAt, Instant endsAt,
                    String createdBy, Instant createdAt) { }
