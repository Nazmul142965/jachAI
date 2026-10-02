package com.jachai.talent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCampaignRequest(@NotBlank @Size(max = 150) String title,
                                    @NotBlank String assessmentId) { }
