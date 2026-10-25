package io.github.imzmq.interview.agent.application.contract.extension;

import java.util.Map;

/**
 * Consumer-owned extension point for rendering the generic Agent task-routing prompt.
 */
public interface AgentTaskPromptRenderer {

    String renderTaskRouterPrompt(Map<String, Object> variables);
}
