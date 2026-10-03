package com.jachai.attempt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveTextAnswerRequest(@NotBlank @Size(max = 4000) String text) { }
