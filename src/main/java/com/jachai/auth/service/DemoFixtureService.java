package com.jachai.auth.service;

import com.jachai.academy.dto.CreateBatchAssessmentRequest;
import com.jachai.academy.dto.CreateBatchRequest;
import com.jachai.academy.dto.CreateCourseRequest;
import com.jachai.academy.model.Batch;
import com.jachai.academy.model.BatchAssessment;
import com.jachai.academy.model.BatchEnrollment;
import com.jachai.academy.model.Course;
import com.jachai.academy.service.AcademyCatalogService;
import com.jachai.academy.service.BatchAssessmentService;
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
import com.jachai.shared.error.ApiException;
import com.jachai.talent.dto.CreateCampaignRequest;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.service.TalentCampaignService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Seeds one usable journey per demo tenant through the normal business services. */
@Service
@Profile("demo | prod")
@RequiredArgsConstructor
@Slf4j
public class DemoFixtureService {
    private final MongoTemplate mongo;
    private final QuestionService questions;
    private final AssessmentService assessments;
    private final AssignmentService assignments;
    private final TalentCampaignService campaigns;
    private final AcademyCatalogService catalog;
    private final BatchAssessmentService batchAssessments;

    private static final String ACADEMY_ORG = "demo-org-academy";
    private static final String ACADEMY_STAFF = "demo-account-instructor";
    private static final String ACADEMY_LEARNER = "demo-account-learner";
    private static final String ACADEMY_ASSESSMENT = "Spring Boot foundations — demo assessment";
    private static final String ACADEMY_COURSE = "Java Backend Fundamentals";
    private static final String ACADEMY_BATCH = "Demo cohort";

    public void seed() {
        seedSafely(ACADEMY_ORG, ACADEMY_STAFF, ACADEMY_LEARNER,
                "Which annotation marks a Spring class as a REST controller?",
                ACADEMY_ASSESSMENT, false);
        seedSafely("demo-org-talent", "demo-account-recruiter", "demo-account-candidate",
                "Which HTTP method is normally used to replace a resource?",
                "Backend engineer — demo assessment", true);
        // An Academy does not hand out assessments one learner at a time; it runs cohorts. Without
        // a course, a batch, an enrolled learner and the assessment distributed to that batch,
        // both Academy demo roles sign in to a workspace with nothing in it, and the screens that
        // matter most here — the roster, batch progress, the grading queue — have nothing to show.
        try {
            seedAcademyCohort();
        } catch (ApiException refused) {
            // The code and message are the product's own words for a rejected request, so they are
            // safe to log and they are the only thing that makes this diagnosable. Logging just
            // the class name said "ApiException" and nothing else, which cost me a bisect.
            log.warn("Demo Academy cohort setup incomplete: {} {}", refused.code(), refused.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Demo Academy cohort setup incomplete: {}", exception.getClass().getSimpleName());
        }
    }

    /**
     * Builds the Academy cohort through the same services staff use, so what the demo shows is
     * reachable by hand and nothing here depends on a shape the product does not produce.
     *
     * <p>Every step checks for its own result first, because this runs on every start.
     */
    private void seedAcademyCohort() {
        Assessment assessment = find(ACADEMY_ORG, "title", ACADEMY_ASSESSMENT, Assessment.class);
        // The assessment seed above is wrapped in its own catch, so it can legitimately be absent.
        // There is then nothing to distribute, and a half-built cohort is worse than none.
        if (assessment == null) return;

        Course course = find(ACADEMY_ORG, "name", ACADEMY_COURSE, Course.class);
        String courseId = course != null ? course.id()
                : catalog.createCourse(ACADEMY_STAFF, ACADEMY_ORG, new CreateCourseRequest(
                        ACADEMY_COURSE, "Core Java, Spring and MongoDB.")).id();

        Instant now = Instant.now();
        Batch batch = mongo.findOne(Query.query(Criteria.where("organizationId").is(ACADEMY_ORG)
                .and("courseId").is(courseId).and("name").is(ACADEMY_BATCH)), Batch.class);
        String batchId = batch != null ? batch.id()
                // The batch window has to contain the assessment window with room to spare, not
                // merely match it. Matching the ends exactly fails: the batch instant goes through
                // Mongo and comes back truncated to milliseconds, while the value compared against
                // it here still carries nanoseconds, so "until" lands after the batch end by a
                // fraction of a millisecond and the distribution is rejected as out of window.
                : catalog.createBatch(ACADEMY_STAFF, ACADEMY_ORG, courseId, new CreateBatchRequest(
                        ACADEMY_BATCH, now.minus(30, ChronoUnit.DAYS),
                        now.plus(180, ChronoUnit.DAYS))).id();

        if (!mongo.exists(Query.query(Criteria.where("organizationId").is(ACADEMY_ORG)
                .and("batchId").is(batchId).and("learnerUserId").is(ACADEMY_LEARNER)),
                BatchEnrollment.class)) {
            catalog.enroll(ACADEMY_STAFF, ACADEMY_ORG, courseId, batchId, ACADEMY_LEARNER);
        }

        if (!mongo.exists(Query.query(Criteria.where("organizationId").is(ACADEMY_ORG)
                .and("batchId").is(batchId).and("assessmentId").is(assessment.id())),
                BatchAssessment.class)) {
            // Distributing to the batch is what creates each enrolled learner's assignment, so
            // this one call is what makes the cohort visible from the learner's side too.
            batchAssessments.create(ACADEMY_STAFF, ACADEMY_ORG, courseId, batchId,
                    new CreateBatchAssessmentRequest(assessment.id(), 2,
                            now.minus(1, ChronoUnit.DAYS), now.plus(90, ChronoUnit.DAYS)));
        }
    }

    private void seedSafely(String org, String author, String participant, String prompt,
                            String title, boolean talent) {
        try {
            seedAssessment(org, author, participant, prompt, title, talent);
        } catch (RuntimeException exception) {
            log.warn("Demo example setup incomplete for {}: {}", org, exception.getClass().getSimpleName());
        }
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
