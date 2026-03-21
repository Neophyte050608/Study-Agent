package com.example.interview.service;

import java.time.Instant;
import java.util.List;

public record LearningEvent(
        String eventId,
        String userId,
        LearningSource source,
        String topic,
        int score,
        List<String> weakPoints,
        List<String> familiarPoints,
        String evidence,
        Instant timestamp
) {
}
