package com.jachai.academy.dto;

import com.jachai.academy.model.Batch;
import java.time.Instant;

public record BatchView(String id, String courseId, String name,
                        Instant startsAt, Instant endsAt, Instant createdAt) {
    public static BatchView from(Batch batch) {
        return new BatchView(batch.id(), batch.courseId(), batch.name(), batch.startsAt(),
                batch.endsAt(), batch.createdAt());
    }
}
