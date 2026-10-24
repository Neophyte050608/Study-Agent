package io.github.imzmq.interview.agent.application.contract.extension;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextSchema;

/**
 * Spring-discovered extension that contributes the context schema for one agent mode.
 */
public interface AgentContextSchemaProvider {

    AgentContextSchema schema();
}
