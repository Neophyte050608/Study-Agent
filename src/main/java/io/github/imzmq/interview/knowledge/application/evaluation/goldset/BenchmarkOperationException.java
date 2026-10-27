package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Content-free benchmark failure with a stable error code. */
public final class BenchmarkOperationException extends RuntimeException {

    public BenchmarkOperationException(String code) {
        super(code);
    }

    public BenchmarkOperationException(String code, Throwable cause) {
        super(code, cause);
    }
}
