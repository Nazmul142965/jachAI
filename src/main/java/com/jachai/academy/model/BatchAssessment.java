package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("academy_batch_assessments")
@CompoundIndexes({
        @CompoundIndex(name = "batch_assessment_org_batch_version_unique",
                def = "{'organizationId': 1, 'batchId': 1, 'assessmentVersionId': 1}", unique = true),
        @CompoundIndex(name = "batch_assessment_org_batch_created",
                def = "{'organizationId': 1, 'batchId': 1, 'createdAt': -1}")
})
public record BatchAssessment(@Id String id, String organizationId, String courseId, String batchId,
                              String assessmentId, String assessmentVersionId, String assessmentTitle,
                              int attemptsAllowed, Instant availableFrom, Instant availableUntil,
                              String createdBy, Instant createdAt) { }
