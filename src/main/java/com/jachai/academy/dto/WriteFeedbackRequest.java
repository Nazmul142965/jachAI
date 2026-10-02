package com.jachai.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WriteFeedbackRequest(
        @NotBlank @Size(max = 2000) String summary,
        @NotBlank @Size(max = 2000) String nextSteps) { }
