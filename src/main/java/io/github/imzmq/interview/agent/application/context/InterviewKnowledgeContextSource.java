package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InterviewKnowledgeContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "interview-knowledge";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.KNOWLEDGE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.INTERVIEW) {
            return List.of();
        }
        RAGService.KnowledgePacket packet = InterviewContextAttributes.packet(query);
        if (packet == null) {
            return List.of();
        }
        String text = buildKnowledgeText(packet);
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(new ContextItem(text, 1.0, id(), Map.of(
                "retrievedDocs", String.valueOf(packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size()),
                "webFallbackUsed", String.valueOf(packet.webFallbackUsed())
        )));
    }

    private String buildKnowledgeText(RAGService.KnowledgePacket packet) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "检索上下文", packet.context());
        appendSection(builder, "图片上下文", packet.imageContext());
        appendSection(builder, "证据目录摘要", packet.retrievalEvidence());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("【").append(title).append("】\n").append(value.trim());
    }
}
