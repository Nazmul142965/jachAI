package com.jachai.question.service;

import com.jachai.organization.model.OrganizationRole;
import com.jachai.organization.service.TenantAccessService;
import com.jachai.question.dto.CreateQuestionRequest;
import com.jachai.question.model.Question;
import com.jachai.question.model.QuestionDifficulty;
import com.jachai.question.model.QuestionStatus;
import com.jachai.question.model.QuestionType;
import com.jachai.question.repository.QuestionRepository;
import com.jachai.shared.error.ApiException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuestionService {
    private final QuestionRepository questions;
    private final TenantAccessService access;
    private final MongoTemplate mongo;

    public Question create(String userId, String organizationId, CreateQuestionRequest request) {
        requireAuthor(userId, organizationId);
        List<Question.Option> options = validatedOptions(request);
        return questions.save(new Question(UUID.randomUUID().toString(), organizationId, userId,
                request.topic().trim(), request.prompt().trim(), request.type(), options,
                Set.copyOf(request.correctOptionIds()), QuestionStatus.DRAFT, Instant.now(),
                request.difficulty() == null ? QuestionDifficulty.MEDIUM : request.difficulty()));
    }

    public Question updateDraft(String userId, String organizationId, String questionId,
                                CreateQuestionRequest request) {
        requireAuthor(userId, organizationId);
        List<Question.Option> options = validatedOptions(request);
        Question current = questions.findByIdAndOrganizationId(questionId, organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Question not found"));
        if (current.status() != QuestionStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "QUESTION_APPROVED", "Approved questions cannot be edited");
        }
        Update update = new Update().set("topic", request.topic().trim())
                .set("prompt", request.prompt().trim()).set("type", request.type())
                .set("options", options).set("correctOptionIds", Set.copyOf(request.correctOptionIds()))
                .set("difficulty", request.difficulty() == null ? QuestionDifficulty.MEDIUM : request.difficulty());
        Question saved = mongo.findAndModify(draftQuery(questionId, organizationId), update,
                FindAndModifyOptions.options().returnNew(true), Question.class);
        if (saved == null) {
            throw new ApiException(HttpStatus.CONFLICT, "QUESTION_CHANGED",
                    "Question was approved while editing; reload the question bank");
        }
        return saved;
    }

    private List<Question.Option> validatedOptions(CreateQuestionRequest request) {
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
        return request.options().stream()
                .map(option -> new Question.Option(option.id(), option.text().trim())).toList();
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
        if (question.status() == QuestionStatus.APPROVED) return question;
        Question approved = mongo.findAndModify(draftQuery(questionId, organizationId),
                new Update().set("status", QuestionStatus.APPROVED),
                FindAndModifyOptions.options().returnNew(true), Question.class);
        if (approved == null) {
            return questions.findByIdAndOrganizationId(questionId, organizationId)
                    .filter(value -> value.status() == QuestionStatus.APPROVED)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUESTION_CHANGED",
                            "Question changed while approving; reload the question bank"));
        }
        return approved;
    }

    private Query draftQuery(String questionId, String organizationId) {
        return Query.query(Criteria.where("_id").is(questionId)
                .and("organizationId").is(organizationId).and("status").is(QuestionStatus.DRAFT));
    }

    private void requireAuthor(String userId, String organizationId) {
        access.requireRole(userId, organizationId, OrganizationRole.ORG_ADMIN,
                OrganizationRole.INSTRUCTOR, OrganizationRole.RECRUITER);
    }
}
