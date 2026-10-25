package io.github.imzmq.interview.chat.application.contract;

import io.github.imzmq.interview.chat.application.contract.result.RenderedPrompt;

import java.util.Map;

/**
 * Chat-owned prompt rendering capability for cross-module callers.
 */
public interface PromptRenderer {

    String render(String templateName, Map<String, Object> variables);

    RenderedPrompt renderSplit(
            String systemTemplateName,
            String taskTemplateName,
            Map<String, Object> variables
    );

    String getSystemPrompt(String name);
}
