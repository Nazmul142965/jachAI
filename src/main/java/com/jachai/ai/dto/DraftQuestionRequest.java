package com.jachai.ai.dto;

import com.jachai.question.model.QuestionType;
import com.jachai.question.model.QuestionDifficulty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DraftQuestionRequest(
        @NotBlank @Size(max = 80) String topic,
        @NotBlank @Size(max = 1200) String brief,
        @NotNull QuestionType type,
        QuestionDifficulty difficulty) {
    public DraftQuestionRequest(String topic, String brief, QuestionType type) {
        this(topic, brief, type, null);
    }
}
