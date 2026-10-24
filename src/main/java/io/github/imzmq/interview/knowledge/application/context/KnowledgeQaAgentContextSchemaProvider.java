package io.github.imzmq.interview.knowledge.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSchema;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSchemaProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeQaAgentContextSchemaProvider implements AgentContextSchemaProvider {

    @Override
    public AgentContextSchema schema() {
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
}
