package io.github.imzmq.interview.agent.application.context;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public record AgentRuntimeContext(
        AgentContextMode mode,
        List<FilledContextSlot> slots,
        List<String> trace
) {
    public AgentRuntimeContext {
        mode = mode == null ? AgentContextMode.KNOWLEDGE_QA : mode;
        slots = slots == null ? List.of() : List.copyOf(slots);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public String render() {
        List<String> sections = new ArrayList<>();
        for (FilledContextSlot slot : slots) {
            String section = renderSlot(slot);
            if (!section.isBlank()) {
                sections.add(section);
            }
        }
        return String.join("\n\n", sections);
    }

    public int renderedLength() {
        return render().length();
    }

    public FilledContextSlot slot(AgentContextSlotKind kind) {
        if (kind == null) {
            return null;
        }
        for (FilledContextSlot slot : slots) {
            if (slot.kind() == kind) {
                return slot;
            }
        }
        return null;
    }

    private String renderSlot(FilledContextSlot slot) {
        if (slot == null || slot.skipped() || slot.items().isEmpty()) {
            return "";
        }
        String body = slot.items().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> "- " + item.text())
                .collect(Collectors.joining("\n"));
        if (body.isBlank()) {
            return "";
        }
        return "【" + slot.kind().title() + "】\n" + body;
    }
}
