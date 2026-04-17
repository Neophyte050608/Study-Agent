package io.github.imzmq.interview.stream.runtime;

public interface StreamEventEmitter {

    void emit(String eventType, Object payload);

    default void complete() {
        // Optional terminal hook for transport-backed emitters.
    }

    default void fail(Throwable throwable) {
        // Optional terminal hook for transport-backed emitters.
    }

    default void done() {
        emit(InterviewStreamEventType.DONE.value(), "[DONE]");
    }
}

