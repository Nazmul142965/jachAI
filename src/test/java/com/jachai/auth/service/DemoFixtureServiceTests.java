package com.jachai.auth.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.academy.dto.BatchView;
import com.jachai.academy.dto.CourseView;
import com.jachai.academy.model.Batch;
import com.jachai.academy.model.BatchAssessment;
import com.jachai.academy.model.BatchEnrollment;
import com.jachai.academy.model.Course;
import com.jachai.academy.service.AcademyCatalogService;
import com.jachai.academy.service.BatchAssessmentService;
import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.service.AssessmentService;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.service.AssignmentService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.service.QuestionService;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.dto.CampaignView;
import com.jachai.talent.service.TalentCampaignService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import java.time.Instant;

class DemoFixtureServiceTests {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final QuestionService questions = mock(QuestionService.class);
    private final AssessmentService assessments = mock(AssessmentService.class);
    private final AssignmentService assignments = mock(AssignmentService.class);
    private final TalentCampaignService campaigns = mock(TalentCampaignService.class);
    private final AcademyCatalogService catalog = mock(AcademyCatalogService.class);
    private final BatchAssessmentService batchAssessments = mock(BatchAssessmentService.class);
    private final DemoFixtureService service = new DemoFixtureService(mongo, questions, assessments,
            assignments, campaigns, catalog, batchAssessments);

    /** The cohort seed needs a published Academy assessment to distribute; give it one. */
    private void academyAssessmentExists() {
        Assessment seeded = mock(Assessment.class);
        when(seeded.id()).thenReturn("a1");
        when(seeded.status()).thenReturn(AssessmentStatus.PUBLISHED);
        when(mongo.findOne(any(Query.class), eq(Assessment.class))).thenReturn(seeded);
    }

    @Test
    void freshDemoUsesNormalPublishingAndInvitationServices() {
        Question question = mock(Question.class);
        when(question.id()).thenReturn("q1");
        when(question.status()).thenReturn(QuestionStatus.DRAFT);
        when(questions.create(any(), any(), any())).thenReturn(question);
        Assessment assessment = mock(Assessment.class);
        when(assessment.id()).thenReturn("a1");
        when(assessment.status()).thenReturn(AssessmentStatus.DRAFT);
        when(assessments.create(any(), any(), any())).thenReturn(assessment);
        CampaignView campaignView = mock(CampaignView.class);
        when(campaignView.id()).thenReturn("c1");
        when(campaigns.create(any(), any(), any())).thenReturn(campaignView);
        HiringCampaign campaign = mock(HiringCampaign.class);
        when(campaign.id()).thenReturn("c1");
        when(mongo.findById("c1", HiringCampaign.class)).thenReturn(campaign);

        service.seed();

        verify(questions).approve("demo-account-instructor", "demo-org-academy", "q1");
        verify(questions).approve("demo-account-recruiter", "demo-org-talent", "q1");
        verify(assessments).publish("demo-account-instructor", "demo-org-academy", "a1");
        verify(assessments).publish("demo-account-recruiter", "demo-org-talent", "a1");
        verify(assignments).assign(eq("demo-account-instructor"), eq("demo-org-academy"), eq("a1"), any());
        verify(campaigns).invite(eq("demo-account-recruiter"), eq("demo-org-talent"), eq("c1"), any());
        verify(mongo, never()).insert(any(Object.class));
    }

    @Test
    void existingFixturesDoNotConsumeQuotaAgain() {
        Question question = mock(Question.class);
        when(question.id()).thenReturn("q1");
        when(question.status()).thenReturn(QuestionStatus.APPROVED);
        when(mongo.findOne(any(Query.class), eq(Question.class))).thenReturn(question);
        Assessment assessment = mock(Assessment.class);
        when(assessment.id()).thenReturn("a1");
        when(assessment.status()).thenReturn(AssessmentStatus.PUBLISHED);
        when(mongo.findOne(any(Query.class), eq(Assessment.class))).thenReturn(assessment);
        HiringCampaign campaign = mock(HiringCampaign.class);
        when(campaign.id()).thenReturn("c1");
        when(mongo.findOne(any(Query.class), eq(HiringCampaign.class))).thenReturn(campaign);
        when(mongo.exists(any(Query.class), eq(Assignment.class))).thenReturn(true);

        service.seed();

        verify(assessments, never()).publish(any(), any(), any());
        verify(assignments, never()).assign(any(), any(), any(), any());
        verify(campaigns, never()).invite(any(), any(), any(), any());
        verify(questions, never()).create(any(), any(), any());
    }

    @Test
    void academySetupFailureDoesNotSkipTalentSetup() {
        Question talentQuestion = mock(Question.class);
        when(talentQuestion.id()).thenReturn("talent-q");
        when(talentQuestion.status()).thenReturn(QuestionStatus.APPROVED);
        when(questions.create(any(), any(), any()))
                .thenThrow(new IllegalStateException("academy unavailable"))
                .thenReturn(talentQuestion);
        Assessment talentAssessment = mock(Assessment.class);
        when(talentAssessment.id()).thenReturn("talent-a");
        when(talentAssessment.status()).thenReturn(AssessmentStatus.PUBLISHED);
        when(assessments.create(any(), any(), any())).thenReturn(talentAssessment);
        CampaignView campaign = mock(CampaignView.class);
        when(campaign.id()).thenReturn("talent-c");
        when(campaigns.create(any(), any(), any())).thenReturn(campaign);
        HiringCampaign storedCampaign = mock(HiringCampaign.class);
        when(storedCampaign.id()).thenReturn("talent-c");
        when(mongo.findById("talent-c", HiringCampaign.class)).thenReturn(storedCampaign);

        service.seed();

        verify(questions, times(2)).create(any(), any(), any());
        verify(campaigns).invite(eq("demo-account-recruiter"), eq("demo-org-talent"),
                eq("talent-c"), any());
    }

    @Test
    void academyCohortIsBuiltSoTheWorkspaceIsNotEmpty() {
        academyAssessmentExists();
        when(catalog.createCourse(any(), any(), any()))
                .thenReturn(new CourseView("course-1", "Java Backend Fundamentals", "", Instant.now()));
        when(catalog.createBatch(any(), any(), any(), any()))
                .thenReturn(new BatchView("batch-1", "course-1", "Demo cohort",
                        Instant.now(), Instant.now(), Instant.now()));

        service.seed();

        verify(catalog).createCourse(eq("demo-account-instructor"), eq("demo-org-academy"), any());
        verify(catalog).createBatch(eq("demo-account-instructor"), eq("demo-org-academy"),
                eq("course-1"), any());
        verify(catalog).enroll("demo-account-instructor", "demo-org-academy", "course-1", "batch-1",
                "demo-account-learner");
        // Distributing to the batch is what creates the learner's assignment, so without this call
        // the cohort exists but nothing reaches the learner.
        verify(batchAssessments).create(eq("demo-account-instructor"), eq("demo-org-academy"),
                eq("course-1"), eq("batch-1"), any());
    }

    @Test
    void existingAcademyCohortIsNotRebuiltOnEveryStart() {
        academyAssessmentExists();
        Course course = mock(Course.class);
        when(course.id()).thenReturn("course-1");
        when(mongo.findOne(any(Query.class), eq(Course.class))).thenReturn(course);
        Batch batch = mock(Batch.class);
        when(batch.id()).thenReturn("batch-1");
        when(mongo.findOne(any(Query.class), eq(Batch.class))).thenReturn(batch);
        when(mongo.exists(any(Query.class), eq(BatchEnrollment.class))).thenReturn(true);
        when(mongo.exists(any(Query.class), eq(BatchAssessment.class))).thenReturn(true);

        service.seed();

        verify(catalog, never()).createCourse(any(), any(), any());
        verify(catalog, never()).createBatch(any(), any(), any(), any());
        verify(catalog, never()).enroll(any(), any(), any(), any(), any());
        verify(batchAssessments, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void cohortIsNotStartedWhenThereIsNoAssessmentToDistribute() {
        // The assessment seed runs in its own try/catch, so it can legitimately be absent. A
        // course and batch with nothing in them is worse than no cohort at all.
        service.seed();

        verify(catalog, never()).createCourse(any(), any(), any());
        verify(batchAssessments, never()).create(any(), any(), any(), any(), any());
    }
}
