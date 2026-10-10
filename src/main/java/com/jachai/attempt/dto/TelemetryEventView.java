package com.jachai.attempt.dto;

import com.jachai.attempt.model.TelemetryEvent;
import com.jachai.attempt.model.TelemetryEventType;
import java.time.Instant;

public record TelemetryEventView(TelemetryEventType type, String questionId, Instant receivedAt,
                                 long tabSwitchCount, boolean warning, String warningMessage) {
    public TelemetryEventView(TelemetryEventType type, String questionId, Instant receivedAt) {
        this(type, questionId, receivedAt, 0, false, null);
    }

    public static TelemetryEventView from(TelemetryEvent event) {
        return new TelemetryEventView(event.type(), event.questionId(), event.receivedAt());
    }
}
