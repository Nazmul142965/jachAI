package com.jachai.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCourseRequest(@NotBlank @Size(max = 120) String name,
                                  @Size(max = 2000) String description) { }
