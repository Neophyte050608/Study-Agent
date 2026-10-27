package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;

/** Raised before retrieval when a gold-set dataset is internally inconsistent. */
public final class GoldSetDatasetValidationException extends IllegalArgumentException {

    private final List<String> violations;

    public GoldSetDatasetValidationException(List<String> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }

    private static String buildMessage(List<String> violations) {
        List<String> safeViolations = violations == null ? List.of() : List.copyOf(violations);
        return "Gold-set dataset has " + safeViolations.size() + " violation(s): "
                + String.join("; ", safeViolations);
    }
}
