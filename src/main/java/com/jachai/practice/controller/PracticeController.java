package com.jachai.practice.controller;

import com.jachai.practice.service.PracticeCatalog;
import com.jachai.practice.service.PracticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/practice")
public class PracticeController {
    private final PracticeService practice;

    public record AnswerRequest(@NotNull UUID requestId, @NotBlank String questionId,
                                @NotBlank String optionId) { }
    public record HistoryView(String questionId, String selectedOptionId, String correctOptionId,
                              boolean correct, String explanation, Instant answeredAt) { }
    public record HistoryPage(List<HistoryView> content, int number, int totalPages, long totalElements) { }

    @GetMapping("/questions")
    public List<PracticeCatalog.QuestionView> questions(@AuthenticationPrincipal Jwt jwt) {
        return practice.questions(jwt.getSubject());
    }

    @GetMapping("/usage")
    public PracticeService.Usage usage(@AuthenticationPrincipal Jwt jwt) {
        return practice.usage(jwt.getSubject());
    }

    @GetMapping("/answers/mine")
    public HistoryPage history(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page) {
        Page<HistoryView> result = practice.history(jwt.getSubject(), page).map(answer -> new HistoryView(
                answer.questionId(), answer.selectedOptionId(), answer.correctOptionId(),
                answer.correct(), answer.explanation(), answer.answeredAt()));
        return new HistoryPage(result.getContent(), result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @PostMapping("/answers")
    public PracticeService.Result answer(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody AnswerRequest request) {
        return practice.submit(jwt.getSubject(), request.requestId(), request.questionId(), request.optionId());
    }
}
