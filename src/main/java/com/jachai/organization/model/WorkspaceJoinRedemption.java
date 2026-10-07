package com.jachai.organization.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One durable, retryable join outcome per code and account. */
@Document("workspace_join_redemptions")
@CompoundIndex(name = "join_redemption_code_complete", def = "{'codeId': 1, 'completedAt': 1}")
public record WorkspaceJoinRedemption(@Id String id, String codeId, String userId,
                                      Instant createdAt, Instant completedAt,
                                      @Indexed(expireAfter = "0s") Instant expiresAt) {
}
