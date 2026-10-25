package io.github.imzmq.interview.routing.application.contract.extension;

import java.util.Map;

/** Consumer-owned extension point for rendering prompts used by intent routing. */
public interface IntentRoutingPromptRenderer {

    RenderedRoutingPrompt renderRoutingPrompt(String taskTemplateName, Map<String, Object> variables);

    record RenderedRoutingPrompt(String systemPrompt, String userPrompt) {
    }
}
