package com.jachai.ai.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One day's use of the AI assistant, for one person or for the whole platform.
 *
 * <p>The id carries the owner and the UTC day ({@code assistant:user:<id>:2026-10-10}), so a
 * bucket is found by key, never by scanning. Buckets expire on their own two days after the day
 * they count, which keeps the collection from growing.
 */
@Document("assistant_usage")
public record AssistantUsage(@Id String id, String owner, String utcDay, int used,
                             @Indexed(expireAfter = "0s") Instant expiresAt) { }
