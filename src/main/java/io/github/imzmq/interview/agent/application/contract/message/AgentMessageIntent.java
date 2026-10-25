package io.github.imzmq.interview.agent.application.contract.message;

/** Business-visible intents supported by the asynchronous Agent message channel. */
public enum AgentMessageIntent {
    ROLLING_SUMMARY,
    CHAT_CONTEXT_COMPRESS,
    CROSS_SESSION_MEMORIZE
}
