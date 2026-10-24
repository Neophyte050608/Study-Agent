package io.github.imzmq.interview.chat.application.contract;

public interface ChatContextOperations {
    String buildHistoryContext(String sessionId);

    String buildIntentRoutingContext(String sessionId);
}
