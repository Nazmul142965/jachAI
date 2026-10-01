package com.jachai.attempt.dto;

import java.time.Instant;
import java.util.List;

public record ResultView(String attemptId, boolean released, Integer percentage, Boolean passed,
                         Instant submittedAt, Instant releasedAt, List<TopicScore> topics) {
    public record TopicScore(String topic, int correct, int total) { }
}
