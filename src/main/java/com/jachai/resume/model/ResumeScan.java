package com.jachai.resume.model;

import com.jachai.resume.service.ResumeChecker.Report;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("resume_scans")
@CompoundIndex(name = "resume_owner_created", def = "{'userId': 1, 'createdAt': -1}")
public record ResumeScan(@Id String id, String userId, String fingerprint, String fileName, String jobDescription,
                         String status, Report report, String error, Instant createdAt,
                         @Indexed(expireAfter = "0s") Instant expiresAt) { }
