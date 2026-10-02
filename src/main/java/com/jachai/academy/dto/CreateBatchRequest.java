package com.jachai.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateBatchRequest(@NotBlank @Size(max = 120) String name,
                                 @NotNull Instant startsAt,
                                 @NotNull Instant endsAt) { }
