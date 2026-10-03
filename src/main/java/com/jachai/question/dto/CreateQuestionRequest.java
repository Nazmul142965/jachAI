package com.jachai.question.dto;

import com.jachai.question.model.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

public record CreateQuestionRequest(
        @NotBlank @Size(max = 80) String topic,
        @NotBlank @Size(max = 4000) String prompt,
        @NotNull QuestionType type,
        @NotNull @Size(max = 8) List<@NotNull @Valid OptionInput> options,
        @NotNull Set<@NotBlank String> correctOptionIds) {
    public record OptionInput(@NotBlank @Size(max = 20) String id,
                              @NotBlank @Size(max = 1000) String text) { }
}
