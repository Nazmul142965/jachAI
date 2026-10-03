package com.jachai.assessment.model;

import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionDifficulty;
import com.jachai.question.model.QuestionType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("assessment_versions")
@CompoundIndexes({
        @CompoundIndex(name = "version_org_assessment", def = "{'organizationId': 1, 'assessmentId': 1}"),
        @CompoundIndex(name = "version_org_assessment_number", def = "{'organizationId': 1, 'assessmentId': 1, 'version': -1}")
})
public record AssessmentVersion(
        @Id String id,
        String organizationId,
        String assessmentId,
        int version,
        String title,
        int durationMinutes,
        int passingPercentage,
        ResultReleasePolicy resultReleasePolicy,
        List<QuestionSnapshot> questions,
        Instant publishedAt,
        String instructions) {

    public AssessmentVersion(String id, String organizationId, String assessmentId, int version,
                             String title, int durationMinutes, int passingPercentage,
                             ResultReleasePolicy resultReleasePolicy,
                             List<QuestionSnapshot> questions, Instant publishedAt) {
        this(id, organizationId, assessmentId, version, title, durationMinutes,
                passingPercentage, resultReleasePolicy, questions, publishedAt, "");
    }

    public record QuestionSnapshot(String questionId, String topic, String prompt, QuestionType type,
                                   List<Question.Option> options, Set<String> correctOptionIds,
                                   QuestionDifficulty difficulty) {
        public QuestionSnapshot(String questionId, String topic, String prompt, QuestionType type,
                                List<Question.Option> options, Set<String> correctOptionIds) {
            this(questionId, topic, prompt, type, options, correctOptionIds, null);
        }
    }
}
