package io.github.imzmq.interview.skill.core;

public interface ExecutableSkill {

    SkillDefinition definition();

    SkillExecutionResult execute(SkillExecutionContext context);
}

