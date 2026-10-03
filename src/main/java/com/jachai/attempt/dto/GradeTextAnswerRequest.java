package com.jachai.attempt.dto;

import jakarta.validation.constraints.NotNull;

public record GradeTextAnswerRequest(@NotNull Boolean correct) { }
