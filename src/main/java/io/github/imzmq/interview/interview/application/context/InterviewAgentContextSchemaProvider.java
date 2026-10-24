package io.github.imzmq.interview.interview.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSchema;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSchemaProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InterviewAgentContextSchemaProvider implements AgentContextSchemaProvider {

    @Override
    public AgentContextSchema schema() {
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
}
