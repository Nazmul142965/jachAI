package com.jachai.auth.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jachai.assessment.model.Assessment;
import com.jachai.assessment.model.AssessmentStatus;
import com.jachai.assessment.service.AssessmentService;
import com.jachai.attempt.model.Assignment;
import com.jachai.attempt.service.AssignmentService;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.service.QuestionService;
import com.jachai.talent.model.HiringCampaign;
import com.jachai.talent.service.TalentCampaignService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class DemoFixtureServiceTests {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final QuestionService questions = mock(QuestionService.class);
    private final AssessmentService assessments = mock(AssessmentService.class);
    private final AssignmentService assignments = mock(AssignmentService.class);
    private final TalentCampaignService campaigns = mock(TalentCampaignService.class);
    private final DemoFixtureService service = new DemoFixtureService(mongo, questions, assessments,
            assignments, campaigns);

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
}
