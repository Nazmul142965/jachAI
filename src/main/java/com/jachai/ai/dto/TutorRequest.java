package com.jachai.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TutorRequest(@NotBlank @Size(max = 1200) String question,
                           @Size(max = 6) List<@NotNull @Valid Turn> history) {
    public TutorRequest(String question) {
        this(question, List.of());
    }

    public record Turn(@NotNull Role role, @NotBlank @Size(max = 800) String content) { }

    public enum Role { LEARNER, TUTOR }
}
