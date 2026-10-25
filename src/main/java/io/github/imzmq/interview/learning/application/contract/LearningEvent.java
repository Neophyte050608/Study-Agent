package io.github.imzmq.interview.learning.application.contract;

import java.time.Instant;
import java.util.List;

/** Immutable learning evidence accepted by the learning-profile module. */
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
