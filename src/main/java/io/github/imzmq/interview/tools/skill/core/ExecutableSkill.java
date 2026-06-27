package io.github.imzmq.interview.tools.skill.core;

public interface ExecutableSkill {

    SkillDefinition definition();

    SkillExecutionResult execute(SkillExecutionContext context);
}
