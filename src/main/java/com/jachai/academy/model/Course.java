package com.jachai.academy.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("academy_courses")
@CompoundIndex(name = "course_org_key_unique", def = "{'organizationId': 1, 'nameKey': 1}", unique = true)
public record Course(@Id String id, String organizationId, String name, String nameKey,
                     String description, String createdBy, Instant createdAt) { }
