package com.jachai.assessment.dto;

import com.jachai.assessment.model.ResultReleasePolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAssessmentRequest(
        @NotBlank @Size(max = 150) String title,
        @Min(1) @Max(180) int durationMinutes,
        @Min(0) @Max(100) int passingPercentage,
        @NotEmpty @Size(max = 100) List<@NotBlank String> questionIds,
        ResultReleasePolicy resultReleasePolicy,
        @Size(max = 2000) String instructions) {
    public CreateAssessmentRequest(String title, int durationMinutes, int passingPercentage,
                                   List<String> questionIds, ResultReleasePolicy resultReleasePolicy) {
        this(title, durationMinutes, passingPercentage, questionIds, resultReleasePolicy, null);
    }
}
