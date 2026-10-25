package io.github.imzmq.interview.search.application.contract;

/** Generic public gateway for pluggable tool execution. */
public interface ToolGateway<I, O> {
    O run(I input);
}
