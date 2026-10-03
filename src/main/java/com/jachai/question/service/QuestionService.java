package com.jachai.question.service;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.dto.CreateQuestionRequest;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.model.QuestionType;
import com.jachai.question.repository.QuestionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class QuestionService {
    private final QuestionRepository questions;
    private final TenantAccessService access;

    public QuestionService(QuestionRepository questions, TenantAccessService access) {
        this.questions = questions;
        this.access = access;
    }

    public Question create(String userId, String organizationId, CreateQuestionRequest request) {
        requireAuthor(userId, organizationId);
        if (request.type() == null || request.options() == null || request.correctOptionIds() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", "Invalid question configuration");
        }
        Set<String> optionIds = new HashSet<>();
        request.options().forEach(option -> optionIds.add(option.id()));
        boolean shortAnswer = request.type() == QuestionType.SHORT_ANSWER;
        boolean invalid = shortAnswer
                ? !request.options().isEmpty() || !request.correctOptionIds().isEmpty()
                : request.options().size() < 2 || optionIds.size() != request.options().size()
                    || !optionIds.containsAll(request.correctOptionIds())
                    || request.correctOptionIds().isEmpty()
                    || (request.type() == QuestionType.SINGLE_CHOICE && request.correctOptionIds().size() != 1);
        if (invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION", "Invalid option or answer configuration");
        }
        List<Question.Option> options = request.options().stream()
                .map(option -> new Question.Option(option.id(), option.text().trim())).toList();
        return questions.save(new Question(UUID.randomUUID().toString(), organizationId, userId,
                request.topic().trim(), request.prompt().trim(), request.type(), options,
                Set.copyOf(request.correctOptionIds()), QuestionStatus.DRAFT, Instant.now()));
    }

    public Page<Question> list(String userId, String organizationId, QuestionStatus status, Pageable pageable) {
        requireAuthor(userId, organizationId);
        return status == null ? questions.findByOrganizationId(organizationId, pageable)
                : questions.findByOrganizationIdAndStatus(organizationId, status, pageable);
    }

    public Question approve(String userId, String organizationId, String questionId) {
        requireAuthor(userId, organizationId);
        Question question = questions.findByIdAndOrganizationId(questionId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Question not found"));
        return question.status() == QuestionStatus.APPROVED ? question : questions.save(question.approved());
    }

    private void requireAuthor(String userId, String organizationId) {
        access.requireRole(userId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
    }
}
