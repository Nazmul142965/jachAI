package com.jachai.question.controller;

import com.jachai.question.dto.CreateQuestionRequest;
import com.jachai.question.model.Question;
import com.jachai.question.service.QuestionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/questions")
public class QuestionController {
    private final QuestionService questions;

    public QuestionController(QuestionService questions) { this.questions = questions; }

    @GetMapping
    public List<Question> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId) {
        return questions.list(jwt.getSubject(), organizationId);
    }

    @PostMapping
    public ResponseEntity<Question> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String organizationId, @Valid @RequestBody CreateQuestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(questions.create(jwt.getSubject(), organizationId, request));
    }

    @PostMapping("/{questionId}/approve")
    public Question approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String organizationId,
                            @PathVariable String questionId) {
        return questions.approve(jwt.getSubject(), organizationId, questionId);
    }
}
