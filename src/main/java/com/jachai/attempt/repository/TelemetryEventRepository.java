package com.jachai.attempt.repository;

import com.jachai.attempt.model.TelemetryEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TelemetryEventRepository extends MongoRepository<TelemetryEvent, String> {
    long countByOrganizationIdAndAttemptIdAndExpiresAtAfter(
            String organizationId, String attemptId, Instant now);
    List<TelemetryEvent> findByOrganizationIdAndAttemptIdAndExpiresAtAfterOrderByReceivedAtAsc(
            String organizationId, String attemptId, Instant now);
    Page<TelemetryEvent> findByOrganizationIdAndAttemptIdAndExpiresAtAfter(
            String organizationId, String attemptId, Instant now, Pageable pageable);
}
