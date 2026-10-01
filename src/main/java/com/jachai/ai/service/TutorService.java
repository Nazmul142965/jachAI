package com.jachai.ai.service;

import com.jachai.ai.dto.TutorReply;
import com.jachai.ai.dto.TutorRequest;
import com.jachai.assessment.service.PublishedVersionStore;
import com.jachai.attempt.model.Attempt;
import com.jachai.attempt.model.AttemptStatus;
import com.jachai.attempt.repository.AttemptRepository;
import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TutorService {
    private static final String INSTRUCTION = """
            You are a patient technical tutor. Explain concepts clearly and give one small example.
            Do not claim to change official assessment scores. Do not ask for secrets.
            You are given only topic names, not an assessment answer key.
            If asked for the exact answer to an assessment item, explain the underlying concept
            instead of giving an option letter or an exact answer.
            Keep the reply concise and under 350 words.
            """;

    private final TenantAccessService access;
    private final AttemptRepository attempts;
    private final PublishedVersionStore versions;
    private final AiQuotaService quota;
    private final AiService ai;

    public TutorReply explain(String userId, String organizationId, String attemptId, TutorRequest request) {
        access.requireRole(userId, organizationId, OrganizationRole.LEARNER);
        Attempt attempt = attempts.findByIdAndOrganizationIdAndUserId(attemptId, organizationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attempt not found"));
        if (attempt.status() != AttemptStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT, "TUTOR_NOT_READY",
                    "AI tutor is available after the attempt is submitted");
        }
        boolean active = attempts.findByOrganizationIdAndUserIdAndStatus(
                        organizationId, userId, AttemptStatus.IN_PROGRESS).stream()
                .anyMatch(other -> Instant.now().isBefore(other.deadline()));
        if (active) {
            throw new ApiException(HttpStatus.CONFLICT, "TUTOR_NOT_READY",
                    "AI tutor is unavailable during an active attempt");
        }
        String topics = versions.get(organizationId, attempt.assessmentVersionId()).questions().stream()
                .map(question -> question.topic()).distinct().limit(8)
                .collect(Collectors.joining(", "));
        quota.consume(organizationId, userId);
        String explanation = ai.generate(AiUseCase.TUTOR, INSTRUCTION,
                "Relevant topics: " + topics + "\nLearner question: " + request.question());
        return new TutorReply(explanation);
    }
}
