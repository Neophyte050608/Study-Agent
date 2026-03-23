package com.example.interview.intent;

import java.util.List;
import java.util.Map;

public record IntentRoutingDecision(
        boolean fallbackToLegacy,
        String taskType,
        double confidence,
        String reason,
        Map<String, Object> slots,
        List<IntentCandidate> candidates,
        boolean askClarification,
        String clarificationQuestion,
        List<Map<String, String>> clarificationOptions
) {

    public static IntentRoutingDecision fallback() {
        return new IntentRoutingDecision(true, "", 0D, "", Map.of(), List.of(), false, "", List.of());
    }
}
