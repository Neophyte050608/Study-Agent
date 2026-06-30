package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.application.context.DynamicKnowledgeContextBuilder;
import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeContextSource implements AgentContextSource {

    private final DynamicKnowledgeContextBuilder dynamicContextBuilder;

    public KnowledgeContextSource(DynamicKnowledgeContextBuilder dynamicContextBuilder) {
        this.dynamicContextBuilder = dynamicContextBuilder;
    }

    @Override
    public String id() {
        return "knowledge-context";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.KNOWLEDGE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        KnowledgeContextPacket packet = KnowledgeQaContextAttributes.packet(query);
        if (packet == null) {
            return List.of();
        }
        TurnAnalysis analysis = KnowledgeQaContextAttributes.analysis(query);
        String contextPolicy = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.CONTEXT_POLICY);
        String sessionId = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.SESSION_ID);
        String text = (!sessionId.isBlank() && dynamicContextBuilder != null)
                ? dynamicContextBuilder.buildDynamicContext(contextPolicy, analysis, packet, sessionId)
                : buildCombinedContext(packet);
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(new ContextItem(text, 1.0, id(), Map.of(
                "retrievalMode", String.valueOf(packet.retrievalModeResolved()),
                "webFallbackUsed", String.valueOf(packet.webFallbackUsed())
        )));
    }

    private String buildCombinedContext(KnowledgeContextPacket packet) {
        StringBuilder contextBuilder = new StringBuilder(packet.context() == null ? "" : packet.context());
        if (packet.imageContext() != null && !packet.imageContext().isBlank()) {
            contextBuilder.append("\n\n相关图片说明:\n").append(packet.imageContext());
            contextBuilder.append("\n注意：你的回答可以引用上述图片，使用 [图N] 标记。系统会自动将对应图片内联展示给用户。");
        }
        return contextBuilder.toString();
    }
}
