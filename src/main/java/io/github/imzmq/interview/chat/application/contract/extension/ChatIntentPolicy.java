package io.github.imzmq.interview.chat.application.contract.extension;

/** Chat-owned policy used by business handlers to recognize an explicit scenario switch. */
public interface ChatIntentPolicy {
    boolean looksLikeExplicitModeSwitch(String content);
}
