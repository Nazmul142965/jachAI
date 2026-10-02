package com.jachai.academy.dto;

import jakarta.validation.constraints.NotBlank;

public record EnrollLearnerRequest(@NotBlank String learnerUserId) { }
