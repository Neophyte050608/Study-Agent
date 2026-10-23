package io.github.imzmq.interview.routing.application.contract.result;

import java.util.List;
import java.util.Objects;

public record IntentRecord(
        String sessionId,
        String rawQuery,
        TurnRelation relation,
        String standaloneQuery,
        List<IntentTask> tasks,
        double confidence,
        String reason,
        DecisionSource source
) {

    public IntentRecord {
        sessionId = sessionId == null ? "" : sessionId;
        rawQuery = rawQuery == null ? "" : rawQuery;
        relation = Objects.requireNonNull(relation, "relation");
        standaloneQuery = standaloneQuery == null || standaloneQuery.isBlank() ? rawQuery : standaloneQuery;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        reason = reason == null ? "" : reason;
        source = Objects.requireNonNull(source, "source");
        if (!Double.isFinite(confidence) || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public static IntentRecord fallback(String sessionId, String rawQuery, String reason) {
        return new IntentRecord(
                sessionId,
                rawQuery,
                TurnRelation.NEW_TOPIC,
                rawQuery,
                List.of(),
                0D,
                reason,
                DecisionSource.FALLBACK_RULE
        );
    }
}
