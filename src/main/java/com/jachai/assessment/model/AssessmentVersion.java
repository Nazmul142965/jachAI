package com.jachai.assessment.model;

import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("assessment_versions")
@CompoundIndex(name = "version_org_assessment", def = "{'organizationId': 1, 'assessmentId': 1}")
public record AssessmentVersion(
        @Id String id,
        String organizationId,
        String assessmentId,
        int version,
        String title,
        int durationMinutes,
        int passingPercentage,
        List<QuestionSnapshot> questions,
        Instant publishedAt) {

    public record QuestionSnapshot(String questionId, String topic, String prompt, QuestionType type,
                                   List<Question.Option> options, Set<String> correctOptionIds) { }
}
