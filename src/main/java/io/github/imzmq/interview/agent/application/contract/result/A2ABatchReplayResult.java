package io.github.imzmq.interview.agent.application.contract.result;

import java.util.List;

/** Summary of a batch DLQ replay request. */
public record A2ABatchReplayResult(
        int total,
        int success,
        int failed,
        List<Integer> failedIndexes
) {
    public A2ABatchReplayResult {
        failedIndexes = failedIndexes == null ? List.of() : List.copyOf(failedIndexes);
    }
}
