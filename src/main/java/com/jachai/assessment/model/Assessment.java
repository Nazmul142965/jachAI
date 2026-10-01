package com.jachai.assessment.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("assessments")
@CompoundIndex(name = "assessment_org_status", def = "{'organizationId': 1, 'status': 1}")
public record Assessment(
        @Id String id,
        String organizationId,
        String authorUserId,
        String title,
        int durationMinutes,
        int passingPercentage,
        List<String> questionIds,
        ResultReleasePolicy resultReleasePolicy,
        AssessmentStatus status,
        int latestVersion,
        Instant createdAt,
        @Version Long revision) {

    public Assessment published(int version) {
        return new Assessment(id, organizationId, authorUserId, title, durationMinutes,
                passingPercentage, questionIds, resultReleasePolicy, AssessmentStatus.PUBLISHED, version, createdAt, revision);
    }
}
