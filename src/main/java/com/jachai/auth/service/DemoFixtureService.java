package com.jachai.auth.service;

import com.jachai.assessment.dto.CreateAssessmentRequest;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.model.ResultReleasePolicy;
import com.jachai.assessment.service.AssessmentService;
import com.jachai.attempt.dto.CreateAssignmentRequest;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.service.AssignmentService;
import com.jachai.question.dto.CreateQuestionRequest;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionDifficulty;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.model.QuestionType;
import com.jachai.question.service.QuestionService;
import com.jachai.talent.dto.CreateCampaignRequest;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.service.TalentCampaignService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Seeds one usable journey per demo tenant through the normal business services. */
@Service
@Profile("demo & !prod")
@RequiredArgsConstructor
public class DemoFixtureService {
    private final MongoTemplate mongo;
    private final QuestionService questions;
    private final AssessmentService assessments;
    private final AssignmentService assignments;
    private final TalentCampaignService campaigns;

    public void seed() {
        seedAssessment("demo-org-academy", "demo-account-instructor", "demo-account-learner",
                "Which annotation marks a Spring class as a REST controller?",
                "Spring Boot foundations — demo assessment", false);
        seedAssessment("demo-org-talent", "demo-account-recruiter", "demo-account-candidate",
                "Which HTTP method is normally used to replace a resource?",
                "Backend engineer — demo assessment", true);
    }

    private void seedAssessment(String org, String author, String participant, String prompt,
                                String title, boolean talent) {
        Question question = find(org, "prompt", prompt, Question.class);
        if (question == null) {
            List<CreateQuestionRequest.OptionInput> options = talent
                    ? List.of(new CreateQuestionRequest.OptionInput("a", "GET"),
                            new CreateQuestionRequest.OptionInput("b", "PUT"),
                            new CreateQuestionRequest.OptionInput("c", "DELETE"))
                    : List.of(new CreateQuestionRequest.OptionInput("a", "@Repository"),
                            new CreateQuestionRequest.OptionInput("b", "@RestController"),
                            new CreateQuestionRequest.OptionInput("c", "@Configuration"));
            question = questions.create(author, org, new CreateQuestionRequest("Backend fundamentals", prompt,
                    QuestionType.SINGLE_CHOICE, options, Set.of("b"), QuestionDifficulty.EASY));
        }
        if (question.status() != QuestionStatus.APPROVED) questions.approve(author, org, question.id());

        Assessment assessment = find(org, "title", title, Assessment.class);
        if (assessment == null) assessment = assessments.create(author, org,
                new CreateAssessmentRequest(title, 15, 60, List.of(question.id()),
                        talent ? ResultReleasePolicy.MANUAL : ResultReleasePolicy.IMMEDIATE,
                        "Demo only: answer the question and review the result in your workspace."));
        if (assessment.status() == AssessmentStatus.DRAFT) assessments.publish(author, org, assessment.id());

        Instant now = Instant.now();
        CreateAssignmentRequest invitation = new CreateAssignmentRequest(participant, 2,
                now.minus(1, ChronoUnit.DAYS), now.plus(90, ChronoUnit.DAYS));
        if (talent) {
            HiringCampaign campaign = mongo.findOne(Query.query(Criteria.where("organizationId").is(org)
                    .and("title").is("Backend engineer — demo campaign").and("open").is(true)),
                    HiringCampaign.class);
            if (campaign == null) {
                String campaignId = campaigns.create(author, org,
                        new CreateCampaignRequest("Backend engineer — demo campaign", assessment.id())).id();
                campaign = mongo.findById(campaignId, HiringCampaign.class);
            }
            if (campaign != null && !existsAssignment(org, "campaignId", campaign.id(), participant)) {
                campaigns.invite(author, org, campaign.id(), invitation);
            }
        } else if (!existsAssignment(org, "assessmentId", assessment.id(), participant)) {
            assignments.assign(author, org, assessment.id(), invitation);
        }
    }

    private <T> T find(String org, String field, String value, Class<T> type) {
        return mongo.findOne(Query.query(Criteria.where("organizationId").is(org).and(field).is(value)), type);
    }

    private boolean existsAssignment(String org, String field, String value, String participant) {
        return mongo.exists(Query.query(Criteria.where("organizationId").is(org).and(field).is(value)
                .and("assignedUserId").is(participant)), Assignment.class);
    }
}
