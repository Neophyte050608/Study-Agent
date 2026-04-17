package io.github.imzmq.interview.modelrouting.core;

public class ModelRoutingException extends RuntimeException {
    public ModelRoutingException(String message) {
        super(message);
    }

    public ModelRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}

