package io.github.imzmq.interview.agent.application.contract;

/** Resolves stable instruction blocks for named agent skills. */
public interface AgentSkillInstructions {

    String globalInstruction();

    String resolveSkillBlock(String... names);
}
