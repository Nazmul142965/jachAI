package com.jachai.attempt.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record SaveAnswerRequest(@NotNull Set<String> selectedOptionIds) { }
