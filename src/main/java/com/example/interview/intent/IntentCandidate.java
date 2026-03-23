package com.example.interview.intent;

import java.util.List;

public record IntentCandidate(
        String intentId,
        String taskType,
        double score,
        String reason,
        List<String> missingSlots
) {
}
