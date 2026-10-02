package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("academy_batch_enrollments")
@CompoundIndexes({
        @CompoundIndex(name = "enrollment_org_batch_learner_unique",
                def = "{'organizationId': 1, 'batchId': 1, 'learnerUserId': 1}", unique = true),
        @CompoundIndex(name = "enrollment_org_learner_active",
                def = "{'organizationId': 1, 'learnerUserId': 1, 'active': 1}")
})
public record BatchEnrollment(@Id String id, String organizationId, String courseId,
                              String batchId, String courseName, String batchName,
                              String learnerUserId, boolean active,
                              Instant enrolledAt) { }
