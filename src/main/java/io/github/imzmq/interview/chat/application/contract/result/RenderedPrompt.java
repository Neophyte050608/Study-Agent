package io.github.imzmq.interview.chat.application.contract.result;

/**
 * Separately rendered system and user messages for model APIs that support prompt caching.
 */
public record RenderedPrompt(String systemPrompt, String userPrompt) {
}
