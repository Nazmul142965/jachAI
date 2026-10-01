package com.jachai.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TutorRequest(@NotBlank @Size(max = 1200) String question) { }
