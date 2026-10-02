package com.jachai.attempt.dto;

import com.jachai.attempt.model.TelemetryEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecordTelemetryRequest(@NotNull TelemetryEventType type,
                                     @Size(max = 100) String questionId) { }
