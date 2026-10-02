package com.jachai.attempt.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("attempt_telemetry")
@CompoundIndex(name = "telemetry_org_attempt_received",
        def = "{'organizationId': 1, 'attemptId': 1, 'receivedAt': 1}")
public record TelemetryEvent(@Id String id, String organizationId, String attemptId,
                             String candidateUserId, TelemetryEventType type, String questionId,
                             Instant receivedAt, @Indexed(expireAfter = "0s") Instant expiresAt) { }
