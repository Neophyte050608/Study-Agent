package io.github.imzmq.interview.practice.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSchema;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSchemaProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodingPracticeAgentContextSchemaProvider implements AgentContextSchemaProvider {

    @Override
    public AgentContextSchema schema() {
        return new AgentContextSchema(
                AgentContextMode.CODING_PRACTICE,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(360, 3)),
                        new AgentContextSlot(AgentContextSlotKind.PROFILE, false, new AgentContextSlotFilter(360, 2)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(480, 2))
                )
        );
    }
}
