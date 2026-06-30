package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeContextTest {

    @Test
    void renderUsesSchemaOrderAndSkipsEmptySlots() {
        AgentRuntimeContext context = new AgentRuntimeContext(
                AgentContextMode.KNOWLEDGE_QA,
                List.of(
                        new FilledContextSlot(
                                AgentContextSlotKind.PROFILE,
                                List.of(new ContextItem("弱项：JVM", 1.0, "test", Map.of())),
                                false,
                                ""
                        ),
                        new FilledContextSlot(
                                AgentContextSlotKind.KNOWLEDGE,
                                List.of(new ContextItem("类加载机制说明", 0.8, "test", Map.of())),
                                false,
                                ""
                        ),
                        new FilledContextSlot(
                                AgentContextSlotKind.SESSION_HISTORY,
                                List.of(),
                                true,
                                "source returned empty"
                        )
                ),
                List.of("SESSION_HISTORY:source returned empty")
        );

        String rendered = context.render();

        assertTrue(rendered.indexOf("【用户画像】") < rendered.indexOf("【知识上下文】"));
        assertTrue(rendered.contains("- 弱项：JVM"));
        assertTrue(rendered.contains("- 类加载机制说明"));
        assertFalse(rendered.contains("【会话历史】"));
    }

    @Test
    void renderedLengthReturnsRenderedPromptLength() {
        AgentRuntimeContext context = new AgentRuntimeContext(
                AgentContextMode.KNOWLEDGE_QA,
                List.of(new FilledContextSlot(
                        AgentContextSlotKind.CONSTRAINTS,
                        List.of(new ContextItem("优先基于证据回答", 1.0, "test", Map.of())),
                        false,
                        ""
                )),
                List.of()
        );

        assertEquals(context.render().length(), context.renderedLength());
    }
}
