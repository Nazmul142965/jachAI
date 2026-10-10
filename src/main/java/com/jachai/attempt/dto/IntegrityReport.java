package com.jachai.attempt.dto;

import com.jachai.attempt.model.TelemetryEventType;
import java.util.Map;

public record IntegrityReport(String attemptId, int totalEvents,
                              Map<TelemetryEventType, Long> eventCounts,
                              int clientReportedRapidAnswerIntervals,
                              long tabSwitchCount, boolean tabSwitchLimitReached,
                              String interpretation) { }
