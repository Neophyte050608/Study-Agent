package io.github.imzmq.interview.agent.application.contract.context;

/** Builds the runtime context requested by a consuming agent. */
public interface AgentContextOperations {

    AgentRuntimeContext assemble(AgentContextQuery query);
}
