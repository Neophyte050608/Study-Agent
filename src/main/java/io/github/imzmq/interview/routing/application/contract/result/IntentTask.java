package io.github.imzmq.interview.routing.application.contract.result;

import java.util.Map;

public record IntentTask(String intentLeafId, Map<String, String> slots, double score) {

    public IntentTask {
        intentLeafId = intentLeafId == null ? "" : intentLeafId.trim();
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        requireProbability(score, "score");
    }

    private static void requireProbability(double value, String field) {
        if (!Double.isFinite(value) || value < 0D || value > 1D) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
