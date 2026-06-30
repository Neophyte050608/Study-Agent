package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class AgentContextAssembler {

    private static final int DEFAULT_GLOBAL_CHAR_BUDGET = 3600;

    private final AgentContextSourceRegistry registry;
    private final int globalCharBudget;
    private final Map<AgentContextMode, AgentContextSchema> schemas;

    public AgentContextAssembler(AgentContextSourceRegistry registry) {
        this(registry, DEFAULT_GLOBAL_CHAR_BUDGET);
    }

    public AgentContextAssembler(AgentContextSourceRegistry registry, int globalCharBudget) {
        this.registry = registry;
        this.globalCharBudget = Math.max(1, globalCharBudget);
        this.schemas = AgentContextSchema.defaults();
    }

    public AgentRuntimeContext assemble(AgentContextQuery query) {
        AgentContextQuery safeQuery = query == null
                ? AgentContextQuery.create(AgentContextMode.KNOWLEDGE_QA, "", Map.of())
                : query;
        AgentContextSchema schema = resolveSchema(safeQuery);
        List<FilledContextSlot> filled = new ArrayList<>();
        List<String> trace = new ArrayList<>();

        for (AgentContextSlot slot : schema.slots()) {
            FilledContextSlot result = fillSlot(slot, safeQuery);
            filled.add(result);
            if (result.skipped()) {
                trace.add(slot.kind().name() + ":" + result.reason());
            }
        }

        filled = applyGlobalBudget(filled);
        return new AgentRuntimeContext(schema.mode(), filled, trace);
    }

    private AgentContextSchema resolveSchema(AgentContextQuery query) {
        Object override = query.attribute("schema");
        if (override instanceof AgentContextSchema schema) {
            return schema;
        }
        return schemas.getOrDefault(query.mode(), AgentContextSchema.knowledgeQa());
    }

    private FilledContextSlot fillSlot(AgentContextSlot slot, AgentContextQuery query) {
        List<ContextItem> items = new ArrayList<>();
        for (AgentContextSource source : registry.sourcesFor(slot.kind())) {
            List<ContextItem> sourceItems = source.fetch(slot, query);
            if (sourceItems != null) {
                sourceItems.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .forEach(items::add);
            }
        }
        items = applyTopK(items, slot.filter().topK());
        items = applyCharBudget(items, slot.filter().charBudget());
        if (items.isEmpty()) {
            return new FilledContextSlot(slot.kind(), List.of(), !slot.required(), "source returned empty");
        }
        return new FilledContextSlot(slot.kind(), items, false, "");
    }

    private List<ContextItem> applyTopK(List<ContextItem> items, int topK) {
        if (topK <= 0 || items.size() <= topK) {
            return items;
        }
        return new ArrayList<>(items.subList(0, topK));
    }

    private List<ContextItem> applyCharBudget(List<ContextItem> items, int charBudget) {
        if (charBudget <= 0) {
            return items;
        }
        int total = 0;
        List<ContextItem> kept = new ArrayList<>();
        for (ContextItem item : items) {
            int next = item.text().length();
            if (!kept.isEmpty() && total + next > charBudget) {
                break;
            }
            if (kept.isEmpty() && next > charBudget) {
                kept.add(new ContextItem(item.text().substring(0, charBudget), item.score(), item.source(), item.metadata()));
                break;
            }
            kept.add(item);
            total += next;
        }
        return kept;
    }

    private List<FilledContextSlot> applyGlobalBudget(List<FilledContextSlot> slots) {
        int total = totalLength(slots);
        if (total <= globalCharBudget) {
            return slots;
        }
        List<FilledContextSlot> mutable = new ArrayList<>(slots);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < mutable.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer index) -> mutable.get(index).kind().trimPriority()).reversed());
        for (Integer index : order) {
            if (total <= globalCharBudget) {
                break;
            }
            FilledContextSlot slot = mutable.get(index);
            if (slot.items().isEmpty()) {
                continue;
            }
            total -= slot.items().stream().mapToInt(item -> item.text().length()).sum();
            mutable.set(index, new FilledContextSlot(slot.kind(), List.of(), true, "global budget exceeded"));
        }
        return mutable;
    }

    private int totalLength(List<FilledContextSlot> slots) {
        int total = 0;
        for (FilledContextSlot slot : slots) {
            for (ContextItem item : slot.items()) {
                total += item.text().length();
            }
        }
        return total;
    }
}
