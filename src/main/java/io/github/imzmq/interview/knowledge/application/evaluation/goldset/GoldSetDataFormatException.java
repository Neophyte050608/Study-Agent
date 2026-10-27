package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.io.IOException;

/** Identifies one invalid non-blank JSONL record without exposing or discarding its content. */
public final class GoldSetDataFormatException extends IOException {

    private final int lineNumber;

    public GoldSetDataFormatException(int lineNumber, String detail) {
        super(message(lineNumber, detail));
        this.lineNumber = lineNumber;
    }

    public GoldSetDataFormatException(int lineNumber, String detail, Throwable cause) {
        super(message(lineNumber, detail), cause);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }

    private static String message(int lineNumber, String detail) {
        return "Invalid gold-set JSONL at line " + lineNumber + ": " + detail;
    }
}
