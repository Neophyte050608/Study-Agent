package io.github.imzmq.interview.agent.application.context;

import java.util.List;
import java.util.Map;

public record AgentContextSchema(
        AgentContextMode mode,
        List<AgentContextSlot> slots
) {
    public AgentContextSchema {
        mode = mode == null ? AgentContextMode.KNOWLEDGE_QA : mode;
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public static AgentContextSchema knowledgeQa() {
        return new AgentContextSchema(
                AgentContextMode.KNOWLEDGE_QA,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(240, 4)),
                        new AgentContextSlot(AgentContextSlotKind.DIALOG_SIGNAL, false, new AgentContextSlotFilter(240, 2)),
                        new AgentContextSlot(AgentContextSlotKind.PROFILE, false, new AgentContextSlotFilter(360, 3)),
                        new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, new AgentContextSlotFilter(2400, 4)),
                        new AgentContextSlot(AgentContextSlotKind.SESSION_HISTORY, false, new AgentContextSlotFilter(800, 2))
                )
        );
    }

    public static AgentContextSchema interview() {
        return new AgentContextSchema(
                AgentContextMode.INTERVIEW,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(240, 4)),
                        new AgentContextSlot(AgentContextSlotKind.PROFILE, false, new AgentContextSlotFilter(480, 2)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(700, 3)),
                        new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, new AgentContextSlotFilter(2200, 3))
                )
        );
    }


    public static AgentContextSchema codingPractice() {
        return new AgentContextSchema(
                AgentContextMode.CODING_PRACTICE,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(360, 3)),
                        new AgentContextSlot(AgentContextSlotKind.PROFILE, false, new AgentContextSlotFilter(360, 2)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(480, 2))
                )
        );
    }


    public static AgentContextSchema toolTask() {
        return new AgentContextSchema(
                AgentContextMode.TOOL_TASK,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(600, 3)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(900, 3)),
                        new AgentContextSlot(AgentContextSlotKind.TOOL_STATE, false, new AgentContextSlotFilter(700, 3)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_MEMORY, false, new AgentContextSlotFilter(1200, 4))
                )
        );
    }

    public static Map<AgentContextMode, AgentContextSchema> defaults() {
        return Map.of(
                AgentContextMode.KNOWLEDGE_QA, knowledgeQa(),
                AgentContextMode.INTERVIEW, interview(),
                AgentContextMode.CODING_PRACTICE, codingPractice(),
                AgentContextMode.TOOL_TASK, toolTask()
        );
    }
}
