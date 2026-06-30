package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextAssemblerTest {

    @Test
    void assembleFillsSlotsInSchemaOrder() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                fixedSource("knowledge", AgentContextSlotKind.KNOWLEDGE, "知识内容"),
                fixedSource("profile", AgentContextSlotKind.PROFILE, "画像内容")
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry);

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "JVM 类加载",
                Map.of()
        ));

        String rendered = context.render();
        assertTrue(rendered.indexOf("【用户画像】") < rendered.indexOf("【知识上下文】"));
        assertTrue(rendered.contains("画像内容"));
        assertTrue(rendered.contains("知识内容"));
    }

    @Test
    void assembleTrimsSingleSlotByCharBudget() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                sourceWithItems("knowledge", AgentContextSlotKind.KNOWLEDGE, List.of("12345", "67890", "abcde"))
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry, 500);
        AgentContextSchema schema = new AgentContextSchema(
                AgentContextMode.KNOWLEDGE_QA,
                List.of(new AgentContextSlot(
                        AgentContextSlotKind.KNOWLEDGE,
                        false,
                        new AgentContextSlotFilter(10, 0)
                ))
        );

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of("schema", schema)
        ));

        FilledContextSlot slot = context.slot(AgentContextSlotKind.KNOWLEDGE);
        assertEquals(2, slot.items().size());
        assertEquals("12345", slot.items().get(0).text());
        assertEquals("67890", slot.items().get(1).text());
    }

    @Test
    void assembleTrimsLowPrioritySlotsWhenGlobalBudgetExceeded() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                fixedSource("constraints", AgentContextSlotKind.CONSTRAINTS, "必须基于证据"),
                fixedSource("history", AgentContextSlotKind.SESSION_HISTORY, "很长的历史内容很长的历史内容")
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry, 12);
        AgentContextSchema schema = new AgentContextSchema(
                AgentContextMode.KNOWLEDGE_QA,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, AgentContextSlotFilter.none()),
                        new AgentContextSlot(AgentContextSlotKind.SESSION_HISTORY, false, AgentContextSlotFilter.none())
                )
        );

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of("schema", schema)
        ));

        assertTrue(context.render().contains("必须基于证据"));
        assertFalse(context.render().contains("很长的历史内容"));
    }

    @Test
    void assembleUsesInterviewSchemaOrder() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                fixedSource("profile", AgentContextSlotKind.PROFILE, "画像"),
                fixedSource("strategy", AgentContextSlotKind.TASK_PLAN, "策略"),
                fixedSource("knowledge", AgentContextSlotKind.KNOWLEDGE, "知识"),
                fixedSource("constraints", AgentContextSlotKind.CONSTRAINTS, "约束")
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry);

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of()
        ));

        String rendered = context.render();
        assertTrue(rendered.indexOf("【硬性约束】") < rendered.indexOf("【用户画像】"));
        assertTrue(rendered.indexOf("【用户画像】") < rendered.indexOf("【任务规划】"));
        assertTrue(rendered.indexOf("【任务规划】") < rendered.indexOf("【知识上下文】"));
    }

    private AgentContextSource fixedSource(String id, AgentContextSlotKind kind, String text) {
        return sourceWithItems(id, kind, List.of(text));
    }

    private AgentContextSource sourceWithItems(String id, AgentContextSlotKind kind, List<String> texts) {
        return new AgentContextSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean supports(AgentContextSlotKind candidate) {
                return candidate == kind;
            }

            @Override
            public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
                return texts.stream()
                        .map(text -> new ContextItem(text, 1.0, id, Map.of()))
                        .toList();
            }
        };
    }
}
