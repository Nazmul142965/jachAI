package com.jachai.talent.dto;

import com.jachai.talent.model.ReviewDecisionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(@NotBlank String attemptId, @NotNull ReviewDecisionType decision,
                            @Size(max = 1000) String note) { }
