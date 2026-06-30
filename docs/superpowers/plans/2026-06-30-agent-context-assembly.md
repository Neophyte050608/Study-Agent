# Agent Context Assembly Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a lightweight Agent context assembly module and use it as the first step toward schema-driven prompt context construction.

**Architecture:** Add focused context model types under `agent.application.context`, then add an assembler that fills ordered slots from registered sources with deterministic rendering and character-budget trimming. The first production integration will be Knowledge QA only; existing retrieval, topic tracking, and dynamic knowledge context services remain the source of truth and are wrapped instead of rewritten.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven, existing `PromptManager`, `KnowledgeQaAgent`, `DynamicKnowledgeContextBuilder`, `LearningProfileAgent`.

---

## File Structure

Create core model files:

- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextMode.java` — supported runtime context modes.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSlotKind.java` — supported context slot kinds.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSlotFilter.java` — per-slot budget and topK settings.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSlot.java` — schema slot definition.
- `src/main/java/io/github/imzmq/interview/agent/application/context/ContextItem.java` — one rendered context item plus source metadata.
- `src/main/java/io/github/imzmq/interview/agent/application/context/FilledContextSlot.java` — filled slot result.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentRuntimeContext.java` — structured assembly result and renderer.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java` — ordered schema per mode.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextQuery.java` — per-request input snapshot.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSource.java` — source SPI.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSourceRegistry.java` — Spring-backed source registry.
- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextAssembler.java` — assembly orchestration.

Create Knowledge QA source files:

- `src/main/java/io/github/imzmq/interview/agent/application/context/KnowledgeQaContextAttributes.java` — typed keys and helpers for Knowledge QA query attributes.
- `src/main/java/io/github/imzmq/interview/agent/application/context/LearningProfileContextSource.java` — fills `PROFILE` from `LearningProfileAgent`.
- `src/main/java/io/github/imzmq/interview/agent/application/context/KnowledgeContextSource.java` — fills `KNOWLEDGE` from `KnowledgeContextPacket` and `DynamicKnowledgeContextBuilder`.
- `src/main/java/io/github/imzmq/interview/agent/application/context/DialogSignalContextSource.java` — fills `DIALOG_SIGNAL` from `TurnAnalysis` and context policy.
- `src/main/java/io/github/imzmq/interview/agent/application/context/BasicConstraintsContextSource.java` — fills light answer constraints.

Modify existing files:

- `src/main/java/io/github/imzmq/interview/agent/runtime/KnowledgeQaAgent.java` — replace duplicated manual context assembly with `AgentContextAssembler`.
- `PACKAGE_CONVENTIONS.md` — document `agent.application.context` ownership.

Create tests:

- `src/test/java/io/github/imzmq/interview/agent/application/context/AgentRuntimeContextTest.java`
- `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`
- `src/test/java/io/github/imzmq/interview/agent/application/context/KnowledgeContextSourceTest.java`
- `src/test/java/io/github/imzmq/interview/agent/application/context/DialogSignalContextSourceTest.java`

---

### Task 1: Add context model and renderer

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextMode.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSlotKind.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSlotFilter.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSlot.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ContextItem.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/FilledContextSlot.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentRuntimeContext.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentRuntimeContextTest.java`

- [ ] **Step 1: Write failing renderer tests**

Create `AgentRuntimeContextTest.java`:

```java
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
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest test
```

Expected: fail because the context model classes do not exist.

- [ ] **Step 3: Add enum types**

Create `AgentContextMode.java`:

```java
package io.github.imzmq.interview.agent.application.context;

public enum AgentContextMode {
    KNOWLEDGE_QA,
    INTERVIEW,
    CODING_PRACTICE,
    TOOL_TASK
}
```

Create `AgentContextSlotKind.java`:

```java
package io.github.imzmq.interview.agent.application.context;

public enum AgentContextSlotKind {
    PROFILE("用户画像", 2),
    SESSION_HISTORY("会话历史", 5),
    KNOWLEDGE("知识上下文", 3),
    DIALOG_SIGNAL("对话信号", 1),
    CONSTRAINTS("硬性约束", 0),
    TOOL_STATE("工具状态", 4),
    TASK_PLAN("任务规划", 2),
    TASK_MEMORY("任务记忆", 4),
    RECALL("相关回忆", 6);

    private final String title;
    private final int trimPriority;

    AgentContextSlotKind(String title, int trimPriority) {
        this.title = title;
        this.trimPriority = trimPriority;
    }

    public String title() {
        return title;
    }

    public int trimPriority() {
        return trimPriority;
    }
}
```

- [ ] **Step 4: Add record model types**

Create `AgentContextSlotFilter.java`:

```java
package io.github.imzmq.interview.agent.application.context;

public record AgentContextSlotFilter(
        int charBudget,
        int topK
) {
    public AgentContextSlotFilter {
        charBudget = Math.max(0, charBudget);
        topK = Math.max(0, topK);
    }

    public static AgentContextSlotFilter none() {
        return new AgentContextSlotFilter(0, 0);
    }
}
```

Create `AgentContextSlot.java`:

```java
package io.github.imzmq.interview.agent.application.context;

public record AgentContextSlot(
        AgentContextSlotKind kind,
        boolean required,
        AgentContextSlotFilter filter
) {
    public AgentContextSlot {
        if (kind == null) {
            throw new IllegalArgumentException("slot kind is required");
        }
        filter = filter == null ? AgentContextSlotFilter.none() : filter;
    }
}
```

Create `ContextItem.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.Map;

public record ContextItem(
        String text,
        double score,
        String source,
        Map<String, String> metadata
) {
    public ContextItem {
        text = text == null ? "" : text.trim();
        source = source == null ? "" : source.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean isBlank() {
        return text.isBlank();
    }
}
```

Create `FilledContextSlot.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public record FilledContextSlot(
        AgentContextSlotKind kind,
        List<ContextItem> items,
        boolean skipped,
        String reason
) {
    public FilledContextSlot {
        if (kind == null) {
            throw new IllegalArgumentException("slot kind is required");
        }
        items = items == null ? List.of() : List.copyOf(items);
        reason = reason == null ? "" : reason;
    }
}
```

- [ ] **Step 5: Add runtime context renderer**

Create `AgentRuntimeContext.java`:

```java
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
```

- [ ] **Step 6: Verify renderer tests pass**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest test
```

Expected: pass.

- [ ] **Step 7: Commit core model**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context \
  src/test/java/io/github/imzmq/interview/agent/application/context/AgentRuntimeContextTest.java
git commit -m "feat(agent): 添加上下文装配模型"
```

---

### Task 2: Add schema, query, source registry, and assembler

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextQuery.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSourceRegistry.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextAssembler.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`

- [ ] **Step 1: Write failing assembler tests**

Create `AgentContextAssemblerTest.java`:

```java
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
```

- [ ] **Step 2: Run failing assembler tests**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: fail because assembler classes do not exist.

- [ ] **Step 3: Add schema and query types**

Create `AgentContextSchema.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.List;
import java.util.Map;

public record AgentContextSchema(
        AgentContextMode mode,
        List<AgentContextSlot> slots
) {
    public AgentContextSchema {
        mode = mode == null ? AgentContextMode.KNOWLEDGE_QA : mode;
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public static AgentContextSchema knowledgeQa() {
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

    public static Map<AgentContextMode, AgentContextSchema> defaults() {
        return Map.of(AgentContextMode.KNOWLEDGE_QA, knowledgeQa());
    }
}
```

Create `AgentContextQuery.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.Map;

public record AgentContextQuery(
        AgentContextMode mode,
        String query,
        Map<String, Object> attributes
) {
    public AgentContextQuery {
        mode = mode == null ? AgentContextMode.KNOWLEDGE_QA : mode;
        query = query == null ? "" : query;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AgentContextQuery create(AgentContextMode mode, String query, Map<String, Object> attributes) {
        return new AgentContextQuery(mode, query, attributes);
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }
}
```

- [ ] **Step 4: Add source SPI and registry**

Create `AgentContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public interface AgentContextSource {
    String id();

    boolean supports(AgentContextSlotKind kind);

    List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query);
}
```

Create `AgentContextSourceRegistry.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentContextSourceRegistry {

    private final List<AgentContextSource> sources;

    public AgentContextSourceRegistry(List<AgentContextSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public List<AgentContextSource> sourcesFor(AgentContextSlotKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<AgentContextSource> matched = new ArrayList<>();
        for (AgentContextSource source : sources) {
            if (source.supports(kind)) {
                matched.add(source);
            }
        }
        return matched;
    }
}
```

- [ ] **Step 5: Add assembler**

Create `AgentContextAssembler.java`:

```java
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
```

- [ ] **Step 6: Verify assembler tests pass**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: pass.

- [ ] **Step 7: Run model and assembler tests together**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest,AgentContextAssemblerTest test
```

Expected: pass.

- [ ] **Step 8: Commit assembler**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context \
  src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java
git commit -m "feat(agent): 添加上下文装配器"
```

---

### Task 3: Add Knowledge QA context sources

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/KnowledgeQaContextAttributes.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/KnowledgeContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/DialogSignalContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/BasicConstraintsContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/KnowledgeContextSourceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/DialogSignalContextSourceTest.java`

- [ ] **Step 1: Write failing source tests that avoid Spring or Mockito**

Create `KnowledgeContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeContextSourceTest {

    @Test
    void fetchFallsBackToPacketContextWhenSessionMissing() {
        KnowledgeContextSource source = new KnowledgeContextSource(null);
        KnowledgeContextPacket packet = packet("本轮检索内容", "图片说明");

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of(KnowledgeQaContextAttributes.PACKET, packet)
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("本轮检索内容"));
        assertTrue(items.get(0).text().contains("相关图片说明"));
        assertTrue(items.get(0).text().contains("[图N]"));
    }

    @Test
    void fetchReturnsEmptyWhenPacketMissing() {
        KnowledgeContextSource source = new KnowledgeContextSource(null);

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of()
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, AgentContextSlotFilter.none());
    }

    private KnowledgeContextPacket packet(String context, String imageContext) {
        return new KnowledgeContextPacket(
                KnowledgeRetrievalMode.RAG_ONLY,
                KnowledgeRetrievalMode.RAG_ONLY,
                false,
                true,
                "",
                "query",
                context,
                imageContext,
                "证据",
                List.of(),
                false,
                1,
                List.of("doc-1")
        );
    }
}
```

Create `DialogSignalContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogSignalContextSourceTest {

    @Test
    void fetchBuildsDialogSignalWithoutExternalDependencies() {
        DialogSignalContextSource source = new DialogSignalContextSource();

        List<ContextItem> items = source.fetch(
                new AgentContextSlot(AgentContextSlotKind.DIALOG_SIGNAL, false, AgentContextSlotFilter.none()),
                AgentContextQuery.create(
                        AgentContextMode.KNOWLEDGE_QA,
                        "query",
                        Map.of(
                                KnowledgeQaContextAttributes.ANALYSIS, TurnAnalysis.firstTurn("JVM"),
                                KnowledgeQaContextAttributes.CONTEXT_POLICY, "SWITCH"
                        )
                )
        );

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("新话题"));
        assertTrue(items.get(0).text().contains("JVM"));
    }

    @Test
    void fetchReturnsEmptyWhenAnalysisMissing() {
        DialogSignalContextSource source = new DialogSignalContextSource();

        List<ContextItem> items = source.fetch(
                new AgentContextSlot(AgentContextSlotKind.DIALOG_SIGNAL, false, AgentContextSlotFilter.none()),
                AgentContextQuery.create(AgentContextMode.KNOWLEDGE_QA, "query", Map.of())
        );

        assertTrue(items.isEmpty());
    }
}
```

- [ ] **Step 2: Run failing source tests**

Run:

```bash
mvn -q -Dtest=KnowledgeContextSourceTest,DialogSignalContextSourceTest test
```

Expected: fail because source classes do not exist.

- [ ] **Step 3: Add Knowledge QA attribute helper**

Create `KnowledgeQaContextAttributes.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;

public final class KnowledgeQaContextAttributes {
    public static final String PACKET = "knowledgeQa.packet";
    public static final String ANALYSIS = "knowledgeQa.analysis";
    public static final String CONTEXT_POLICY = "knowledgeQa.contextPolicy";
    public static final String SESSION_ID = "knowledgeQa.sessionId";
    public static final String USER_ID = "knowledgeQa.userId";
    public static final String CURRENT_TOPIC = "knowledgeQa.currentTopic";

    private KnowledgeQaContextAttributes() {
    }

    public static KnowledgeContextPacket packet(AgentContextQuery query) {
        Object value = query == null ? null : query.attribute(PACKET);
        return value instanceof KnowledgeContextPacket packet ? packet : null;
    }

    public static TurnAnalysis analysis(AgentContextQuery query) {
        Object value = query == null ? null : query.attribute(ANALYSIS);
        return value instanceof TurnAnalysis analysis ? analysis : null;
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
```

- [ ] **Step 4: Add KnowledgeContextSource**

Create `KnowledgeContextSource.java`:

```java
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
```

- [ ] **Step 5: Add DialogSignalContextSource and BasicConstraintsContextSource**

Create `DialogSignalContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DialogSignalContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "dialog-signal";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.DIALOG_SIGNAL;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        TurnAnalysis analysis = KnowledgeQaContextAttributes.analysis(query);
        if (analysis == null) {
            return List.of();
        }
        String contextPolicy = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.CONTEXT_POLICY);
        String signal = buildDialogSignal(contextPolicy, analysis);
        if (signal.isBlank()) {
            return List.of();
        }
        return List.of(new ContextItem(signal, 1.0, id(), Map.of("contextPolicy", contextPolicy)));
    }

    private String buildDialogSignal(String contextPolicy, TurnAnalysis analysis) {
        String policy = normalizePolicy(contextPolicy, analysis);
        return switch (policy) {
            case "CONTINUE" -> "用户正在对「" + analysis.currentTopic() + "」进行追问，请基于历史知识上下文和新检索结果给出连贯的深入解答。";
            case "SWITCH" -> "用户切换到了新话题「" + analysis.currentTopic() + "」，请专注于新检索到的知识回答，不要引用之前话题的内容。";
            case "RETURN" -> "用户回跳到之前讨论过的话题「" + analysis.currentTopic() + "」，请结合之前该话题的知识摘要和新检索结果回答。";
            case "SUMMARY" -> "用户请求总结对话中讨论过的知识，请综合所有历史话题的知识给出全面的总结。";
            case "SAFE_MIN" -> "当前轮请以本轮检索结果为主，谨慎引用历史信息，优先保证回答准确与收敛。";
            default -> "";
        };
    }

    private String normalizePolicy(String contextPolicy, TurnAnalysis analysis) {
        String value = contextPolicy == null ? "" : contextPolicy.trim().toUpperCase();
        if ("CONTINUE".equals(value) || "SWITCH".equals(value) || "RETURN".equals(value)
                || "SUMMARY".equals(value) || "SAFE_MIN".equals(value)) {
            return value;
        }
        return switch (analysis.dialogAct()) {
            case NEW_QUESTION, COMPARISON -> "SWITCH";
            case RETURN -> "RETURN";
            case SUMMARY -> "SUMMARY";
            case FOLLOW_UP, CLARIFICATION -> analysis.topicSwitch() ? "SWITCH" : "CONTINUE";
        };
    }
}
```

Create `BasicConstraintsContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BasicConstraintsContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "basic-constraints";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.CONSTRAINTS;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        return List.of(
                new ContextItem("优先基于给定知识上下文和证据回答；证据不足时明确说明不确定。", 1.0, id(), Map.of()),
                new ContextItem("不要编造不存在的引用、图片编号或文档来源。", 1.0, id(), Map.of())
        );
    }
}
```

- [ ] **Step 6: Verify source tests pass**

Run:

```bash
mvn -q -Dtest=KnowledgeContextSourceTest,DialogSignalContextSourceTest test
```

Expected: pass.

- [ ] **Step 7: Commit Knowledge QA sources**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context \
  src/test/java/io/github/imzmq/interview/agent/application/context/KnowledgeContextSourceTest.java \
  src/test/java/io/github/imzmq/interview/agent/application/context/DialogSignalContextSourceTest.java
git commit -m "feat(agent): 添加知识问答上下文来源"
```

---

### Task 4: Add learning profile source

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/LearningProfileContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/LearningProfileContextSourceTest.java`

- [ ] **Step 1: Write failing test for empty-safe profile source**

Create `LearningProfileContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningProfileContextSourceTest {

    @Test
    void fetchReturnsEmptyWhenNoUserId() {
        LearningProfileContextSource source = new LearningProfileContextSource(null);

        List<ContextItem> items = source.fetch(
                new AgentContextSlot(AgentContextSlotKind.PROFILE, false, AgentContextSlotFilter.none()),
                AgentContextQuery.create(AgentContextMode.KNOWLEDGE_QA, "query", Map.of())
        );

        assertTrue(items.isEmpty());
    }

    @Test
    void supportsOnlyProfileSlot() {
        LearningProfileContextSource source = new LearningProfileContextSource(null);

        assertTrue(source.supports(AgentContextSlotKind.PROFILE));
    }
}
```

- [ ] **Step 2: Run failing test**

Run:

```bash
mvn -q -Dtest=LearningProfileContextSourceTest test
```

Expected: fail because `LearningProfileContextSource` does not exist.

- [ ] **Step 3: Add source implementation**

Create `LearningProfileContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LearningProfileContextSource implements AgentContextSource {

    private final LearningProfileAgent learningProfileAgent;

    public LearningProfileContextSource(LearningProfileAgent learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    @Override
    public String id() {
        return "learning-profile";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.PROFILE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        String userId = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.USER_ID);
        if (userId.isBlank() || learningProfileAgent == null) {
            return List.of();
        }
        String currentTopic = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.CURRENT_TOPIC);
        String snapshot = learningProfileAgent.snapshotForPrompt(userId, currentTopic);
        if (snapshot == null || snapshot.isBlank() || "暂无历史学习画像。".equals(snapshot.trim())) {
            return List.of();
        }
        return List.of(new ContextItem(snapshot, 1.0, id(), Map.of("userId", userId)));
    }
}
```

- [ ] **Step 4: Verify profile source test passes**

Run:

```bash
mvn -q -Dtest=LearningProfileContextSourceTest test
```

Expected: pass.

- [ ] **Step 5: Commit profile source**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/LearningProfileContextSource.java \
  src/test/java/io/github/imzmq/interview/agent/application/context/LearningProfileContextSourceTest.java
git commit -m "feat(agent): 添加学习画像上下文来源"
```

---

### Task 5: Integrate assembler into KnowledgeQaAgent

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/runtime/KnowledgeQaAgent.java`
- Test: add or update `src/test/java/io/github/imzmq/interview/agent/runtime/KnowledgeQaAgentTest.java` if constructor-level unit tests are practical in the local JVM.

- [ ] **Step 1: Add constructor dependency**

In `KnowledgeQaAgent.java`, add imports:

```java
import io.github.imzmq.interview.agent.application.context.AgentContextAssembler;
import io.github.imzmq.interview.agent.application.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.context.AgentRuntimeContext;
import io.github.imzmq.interview.agent.application.context.KnowledgeQaContextAttributes;
```

Add field:

```java
    private final AgentContextAssembler contextAssembler;
```

Add constructor parameter before `@Qualifier("ragRetrieveExecutor") Executor ragRetrieveExecutor`:

```java
                            AgentContextAssembler contextAssembler,
```

Assign it:

```java
        this.contextAssembler = contextAssembler;
```

- [ ] **Step 2: Add private helper to build context variables**

Add this private method near `buildCombinedContext`:

```java
    private Map<String, Object> buildPromptVars(String question,
                                                String history,
                                                String sessionId,
                                                String contextPolicy,
                                                TurnAnalysis analysis,
                                                KnowledgeContextPacket packet) {
        String currentTopic = analysis == null ? extractFallbackTopic(question) : analysis.currentTopic();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(KnowledgeQaContextAttributes.PACKET, packet);
        if (analysis != null) {
            attributes.put(KnowledgeQaContextAttributes.ANALYSIS, analysis);
        }
        attributes.put(KnowledgeQaContextAttributes.CONTEXT_POLICY, contextPolicy == null ? "" : contextPolicy);
        attributes.put(KnowledgeQaContextAttributes.SESSION_ID, sessionId == null ? "" : sessionId);
        attributes.put(KnowledgeQaContextAttributes.CURRENT_TOPIC, currentTopic);

        AgentRuntimeContext runtimeContext = contextAssembler.assemble(AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                question,
                attributes
        ));
        Map<String, Object> vars = new HashMap<>();
        vars.put("question", question);
        vars.put("context", runtimeContext.render());
        vars.put("imageContext", packet.imageContext());
        vars.put("evidence", packet.retrievalEvidence());
        vars.put("history", history != null ? history : "");
        vars.put("dialogSignal", "");
        return vars;
    }
```

This preserves the existing template variable names while moving the composed context into the `context` variable. The separate `dialogSignal` variable is kept as an empty string because the dialog signal is now rendered inside the assembled context under `【对话信号】`.

- [ ] **Step 3: Replace duplicated manual context building in `execute`**

Replace this block in `execute`:

```java
            String combinedContext;
            String dialogSignal = "";
            if (sessionId != null && !sessionId.isBlank()) {
                combinedContext = dynamicContextBuilder.buildDynamicContext(contextPolicy, analysis, packet, sessionId);
                dialogSignal = dynamicContextBuilder.buildDialogSignal(contextPolicy, analysis);
            } else {
                combinedContext = buildCombinedContext(packet);
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("question", question);
            vars.put("context", combinedContext);
            vars.put("imageContext", packet.imageContext());
            vars.put("evidence", packet.retrievalEvidence());
            vars.put("history", history != null ? history : "");
            vars.put("dialogSignal", dialogSignal);
```

with:

```java
            Map<String, Object> vars = buildPromptVars(question, history, sessionId, contextPolicy, analysis, packet);
```

- [ ] **Step 4: Replace duplicated manual context building in `executeStream`**

Make the same replacement in `executeStream`:

```java
            Map<String, Object> vars = buildPromptVars(question, history, sessionId, contextPolicy, analysis, packet);
```

- [ ] **Step 5: Remove unused manual helper if compile reports it unused**

If `buildCombinedContext` is no longer used in `KnowledgeQaAgent`, remove the method from that class. The same fallback logic now lives in `KnowledgeContextSource`.

- [ ] **Step 6: Run focused compile/test checks**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest,AgentContextAssemblerTest,KnowledgeContextSourceTest,DialogSignalContextSourceTest,LearningProfileContextSourceTest test
mvn -q -DskipTests compile
```

Expected: both commands pass.

- [ ] **Step 7: Commit Knowledge QA integration**

```bash
git add src/main/java/io/github/imzmq/interview/agent/runtime/KnowledgeQaAgent.java
git commit -m "refactor(agent): 接入知识问答上下文装配"
```

---

### Task 6: Update package documentation and architecture notes

**Files:**
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `docs/superpowers/specs/2026-06-30-agent-context-assembly-design.md` only if implementation differs from the design.

- [ ] **Step 1: Update placement cheatsheet**

In `PACKAGE_CONVENTIONS.md`, update the Agent bullet from:

```markdown
- Agent configuration/evaluation/skills: `agent.application`
```

to:

```markdown
- Agent configuration/evaluation/skills and runtime context assembly: `agent.application`; context assembly implementation lives in `agent.application.context`.
```

- [ ] **Step 2: Add hard placement note**

In the Knowledge package breakdown section, add:

```markdown
- Agent prompt/runtime context assembly belongs in `agent.application.context`; knowledge-specific retrieval and topic policy remain in `knowledge.application.*` and may be wrapped by context sources.
```

- [ ] **Step 3: Run documentation diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Commit docs**

```bash
git add PACKAGE_CONVENTIONS.md docs/superpowers/specs/2026-06-30-agent-context-assembly-design.md
git commit -m "docs: 记录上下文装配模块归属"
```

---

### Task 7: Final verification and cleanup

**Files:**
- No intended production edits beyond previous tasks.

- [ ] **Step 1: Ensure no injected local memory block is staged**

Run:

```bash
grep -n "claude-mem-context" AGENTS.md || true
git diff -- AGENTS.md
```

Expected: no `claude-mem-context` output and no AGENTS.md diff.

- [ ] **Step 2: Run focused context tests**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest,AgentContextAssemblerTest,KnowledgeContextSourceTest,DialogSignalContextSourceTest,LearningProfileContextSourceTest test
```

Expected: pass.

- [ ] **Step 3: Run architecture test**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 4: Run compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 5: Check diff and status**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: `git diff --check` has no output. Status should show only intentional commits ahead of `origin/main` and no unstaged source changes.

- [ ] **Step 6: Summarize implementation**

Report:

```text
完成：新增 agent.application.context 上下文装配基础模型、assembler、Knowledge QA sources，并接入 KnowledgeQaAgent。
验证：列出本轮实际运行且退出码为 0 的 Maven 命令。
风险：说明尚未迁移 Interview/Coding/Tool Task，后续按设计阶段推进。
```
