package com.jachai.auth.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("user_avatars")
public record UserAvatar(@Id String userId, byte[] image, String contentType, Instant updatedAt) { }
