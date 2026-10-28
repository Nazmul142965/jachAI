package com.jachai.resume.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("resume_reviews")
public record ResumeReview(@Id String id, String userId, String status, String summary,
                           List<Finding> findings, List<String> caveats, String error,
                           Instant startedAt, Instant reviewedAt,
                           @Indexed(expireAfter = "0s") Instant expiresAt) {
    public record Finding(String source, String severity, String title, String evidence,
                          String explanation, String action) { }
}
