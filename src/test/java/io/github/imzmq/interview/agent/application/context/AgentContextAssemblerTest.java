package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSchema;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.AgentRuntimeContext;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;
import io.github.imzmq.interview.agent.application.contract.context.FilledContextSlot;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSchemaProvider;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSource;
import io.github.imzmq.interview.interview.application.context.InterviewAgentContextSchemaProvider;
import io.github.imzmq.interview.knowledge.application.context.KnowledgeQaAgentContextSchemaProvider;
import io.github.imzmq.interview.practice.application.context.CodingPracticeAgentContextSchemaProvider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextAssemblerTest {

    @Test
    void assembleFillsSlotsInSchemaOrder() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                fixedSource("knowledge", AgentContextSlotKind.KNOWLEDGE, "知识内容"),
                fixedSource("profile", AgentContextSlotKind.PROFILE, "画像内容")
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry, providers());

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
        AgentContextAssembler assembler = new AgentContextAssembler(registry, providers(), 500);
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
        AgentContextAssembler assembler = new AgentContextAssembler(registry, providers(), 12);
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
        AgentContextAssembler assembler = new AgentContextAssembler(registry, providers());

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


    @Test
    void defaultsIncludeCodingPracticeSchema() {
        AgentContextSchema schema = new CodingPracticeAgentContextSchemaProvider().schema();

        assertEquals(AgentContextMode.CODING_PRACTICE, schema.mode());
        assertEquals(List.of(
                AgentContextSlotKind.CONSTRAINTS,
                AgentContextSlotKind.PROFILE,
                AgentContextSlotKind.TASK_PLAN
        ), schema.slots().stream().map(AgentContextSlot::kind).toList());
    }

    @Test
    void assembleUsesCodingPracticeSchemaOrder() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                fixedSource("profile", AgentContextSlotKind.PROFILE, "画像"),
                fixedSource("plan", AgentContextSlotKind.TASK_PLAN, "计划"),
                fixedSource("constraints", AgentContextSlotKind.CONSTRAINTS, "约束")
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry, providers());

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of()
        ));

        String rendered = context.render();
        assertTrue(rendered.indexOf("【硬性约束】") < rendered.indexOf("【用户画像】"));
        assertTrue(rendered.indexOf("【用户画像】") < rendered.indexOf("【任务规划】"));
    }


    @Test
    void defaultsIncludeToolTaskSchema() {
        AgentContextSchema schema = new ToolTaskAgentContextSchemaProvider().schema();

        assertEquals(AgentContextMode.TOOL_TASK, schema.mode());
        assertEquals(List.of(
                AgentContextSlotKind.CONSTRAINTS,
                AgentContextSlotKind.TASK_PLAN,
                AgentContextSlotKind.TOOL_STATE,
                AgentContextSlotKind.TASK_MEMORY
        ), schema.slots().stream().map(AgentContextSlot::kind).toList());
    }

    @Test
    void assembleUsesToolTaskSchemaOrder() {
        AgentContextSourceRegistry registry = new AgentContextSourceRegistry(List.of(
                fixedSource("memory", AgentContextSlotKind.TASK_MEMORY, "记忆"),
                fixedSource("state", AgentContextSlotKind.TOOL_STATE, "工具"),
                fixedSource("plan", AgentContextSlotKind.TASK_PLAN, "计划"),
                fixedSource("constraints", AgentContextSlotKind.CONSTRAINTS, "约束")
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(registry, providers());

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of()
        ));

        String rendered = context.render();
        assertTrue(rendered.indexOf("【硬性约束】") < rendered.indexOf("【任务规划】"));
        assertTrue(rendered.indexOf("【任务规划】") < rendered.indexOf("【工具状态】"));
        assertTrue(rendered.indexOf("【工具状态】") < rendered.indexOf("【任务记忆】"));
    }

    @Test
    void constructorRejectsDuplicateSchemaModes() {
        AgentContextSchemaProvider first = new KnowledgeQaAgentContextSchemaProvider();
        AgentContextSchemaProvider duplicate = new KnowledgeQaAgentContextSchemaProvider();

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new AgentContextAssembler(
                        new AgentContextSourceRegistry(List.of()),
                        List.of(first, duplicate)
                )
        );

        assertEquals("duplicate agent context schema: KNOWLEDGE_QA", error.getMessage());
    }

    @Test
    void assembleRejectsModeWithoutRegisteredSchema() {
        AgentContextAssembler assembler = new AgentContextAssembler(
                new AgentContextSourceRegistry(List.of()),
                List.of()
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                assembler.assemble(AgentContextQuery.create(
                        AgentContextMode.INTERVIEW,
                        "query",
                        Map.of()
                ))
        );

        assertEquals("no agent context schema registered for INTERVIEW", error.getMessage());
    }

    @Test
    void querySchemaOverrideDoesNotRequireRegisteredProvider() {
        AgentContextAssembler assembler = new AgentContextAssembler(
                new AgentContextSourceRegistry(List.of(
                        fixedSource("constraints", AgentContextSlotKind.CONSTRAINTS, "override")
                )),
                List.of()
        );
        AgentContextSchema override = new AgentContextSchema(
                AgentContextMode.INTERVIEW,
                List.of(new AgentContextSlot(
                        AgentContextSlotKind.CONSTRAINTS,
                        false,
                        AgentContextSlotFilter.none()
                ))
        );

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of("schema", override)
        ));

        assertEquals(AgentContextMode.INTERVIEW, context.mode());
        assertTrue(context.render().contains("override"));
    }

    private List<AgentContextSchemaProvider> providers() {
        return List.of(
                new KnowledgeQaAgentContextSchemaProvider(),
                new InterviewAgentContextSchemaProvider(),
                new CodingPracticeAgentContextSchemaProvider(),
                new ToolTaskAgentContextSchemaProvider()
        );
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
