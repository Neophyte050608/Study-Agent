package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSchema;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSchemaProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolTaskAgentContextSchemaProvider implements AgentContextSchemaProvider {

    @Override
    public AgentContextSchema schema() {
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
}
