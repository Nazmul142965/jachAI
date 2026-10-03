package com.jachai.billing.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("platform_usage_repair_events")
@CompoundIndex(name = "platform_usage_repair_org_time", def = "{'organizationId': 1, 'occurredAt': -1}")
public record PlatformUsageRepairEvent(@Id String id, String runId, String organizationId,
                                       String actorUserId, PlatformUsageRepairStage stage,
                                       Integer changedBuckets, Instant occurredAt) { }
