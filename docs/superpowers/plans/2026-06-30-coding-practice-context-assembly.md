# Coding Practice Context Assembly Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接入 `CODING_PRACTICE` 上下文装配，让刷题单题生成和批量选择题生成使用统一 `AgentContextAssembler`，同时保持现有 prompt 变量兼容。

**Architecture:** 在 `agent.application.context` 内新增刷题属性约定和三个 context source，并把 `AgentContextSchema.defaults()` 扩展到 `CODING_PRACTICE`。`CodingPracticeService` 只负责标准化刷题入参、调用 assembler、把 `AgentRuntimeContext.render()` 结果传给 prompt 参数，不改变现有 LLM 调用、fallback、答案评估和会话状态机。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven, ArchUnit, existing `PromptManager` and `AgentContextAssembler`.

---

## File Structure

- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributes.java`
  - 刷题上下文属性 key 与读取 helper。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSource.java`
  - 负责输出题目数量、题型、排除主题等硬约束。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSource.java`
  - 负责把已有 `profileSnapshot` 转为 `PROFILE` slot。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSource.java`
  - 负责把 topic、difficulty、questionType、count 转为 `TASK_PLAN` slot。
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java`
  - 增加 `codingPractice()` 和 defaults 映射。
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java`
  - 注入 `AgentContextAssembler`，为 `generateCodingQuestion` 和 `generateBatchQuiz` 添加 `agentContext` prompt 参数。
- Create tests under `src/test/java/io/github/imzmq/interview/agent/application/context/`
  - 三个 source 测试与 schema 测试。
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`
  - 增加 coding schema 顺序测试。
- Modify: `src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java`
  - 构造函数补充 assembler，断言 prompt 参数兼容。
- Modify as needed: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`
  - 如果 `CodingPracticeService` 构造函数变化导致测试编译失败，补入 test assembler。

---

### Task 1: Add coding context attribute helpers

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributes.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributesTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributesTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPracticeContextAttributesTest {

    @Test
    void textReturnsTrimmedValue() {
        AgentContextQuery query = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.TOPIC, "  数组与字符串  ")
        );

        assertEquals("数组与字符串", CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.TOPIC));
    }

    @Test
    void integerParsesNumberAndStringValues() {
        AgentContextQuery numberQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.COUNT, 3)
        );
        AgentContextQuery stringQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.COUNT, "4")
        );

        assertEquals(3, CodingPracticeContextAttributes.integer(numberQuery, CodingPracticeContextAttributes.COUNT, 1));
        assertEquals(4, CodingPracticeContextAttributes.integer(stringQuery, CodingPracticeContextAttributes.COUNT, 1));
    }

    @Test
    void stringListReadsListAndSingleString() {
        AgentContextQuery listQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.EXCLUDED_TOPICS, List.of("递归", " 图 "))
        );
        AgentContextQuery stringQuery = AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.EXCLUDED_TOPICS, " 动态规划 ")
        );

        assertEquals(List.of("递归", "图"), CodingPracticeContextAttributes.stringList(listQuery, CodingPracticeContextAttributes.EXCLUDED_TOPICS));
        assertEquals(List.of("动态规划"), CodingPracticeContextAttributes.stringList(stringQuery, CodingPracticeContextAttributes.EXCLUDED_TOPICS));
        assertTrue(CodingPracticeContextAttributes.stringList(null, CodingPracticeContextAttributes.EXCLUDED_TOPICS).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=CodingPracticeContextAttributesTest test
```

Expected: FAIL because `CodingPracticeContextAttributes` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributes.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public final class CodingPracticeContextAttributes {
    public static final String USER_ID = "codingPractice.userId";
    public static final String TOPIC = "codingPractice.topic";
    public static final String DIFFICULTY = "codingPractice.difficulty";
    public static final String QUESTION_TYPE = "codingPractice.questionType";
    public static final String COUNT = "codingPractice.count";
    public static final String EXCLUDED_TOPICS = "codingPractice.excludedTopics";
    public static final String PROFILE_SNAPSHOT = "codingPractice.profileSnapshot";

    private CodingPracticeContextAttributes() {
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static int integer(AgentContextQuery query, String key, int defaultValue) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static List<String> stringList(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim());
        }
        return List.of();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
mvn -q -Dtest=CodingPracticeContextAttributesTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributes.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributesTest.java
git commit -m "feat(agent): 添加刷题上下文属性"
```

---

### Task 2: Add coding context sources

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSourceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSourceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSourceTest.java`

- [ ] **Step 1: Write the failing source tests**

Create `CodingConstraintsContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingConstraintsContextSourceTest {

    @Test
    void fetchRendersCountQuestionTypeAndExcludedTopics() {
        CodingConstraintsContextSource source = new CodingConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(
                        CodingPracticeContextAttributes.COUNT, 3,
                        CodingPracticeContextAttributes.QUESTION_TYPE, "选择题",
                        CodingPracticeContextAttributes.EXCLUDED_TOPICS, List.of("递归", "图")
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("题目数量：3"));
        assertTrue(text.contains("题型：选择题"));
        assertTrue(text.contains("避免重复主题：递归、图"));
    }

    @Test
    void fetchReturnsEmptyForNonCodingMode() {
        CodingConstraintsContextSource source = new CodingConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "数组",
                Map.of(CodingPracticeContextAttributes.COUNT, 3)
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, AgentContextSlotFilter.none());
    }
}
```

Create `CodingProfileContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingProfileContextSourceTest {

    @Test
    void fetchUsesProfileSnapshotAttribute() {
        CodingProfileContextSource source = new CodingProfileContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, "弱项：边界条件")
        ));

        assertEquals(1, items.size());
        assertEquals("弱项：边界条件", items.get(0).text());
        assertEquals("coding-profile", items.get(0).source());
    }

    @Test
    void fetchIgnoresBlankOrDefaultProfile() {
        CodingProfileContextSource source = new CodingProfileContextSource();

        assertTrue(source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, "   ")
        )).isEmpty());
        assertTrue(source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, "暂无历史学习画像。")
        )).isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.PROFILE, false, AgentContextSlotFilter.none());
    }
}
```

Create `CodingTaskPlanContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTaskPlanContextSourceTest {

    @Test
    void fetchRendersTopicDifficultyTypeAndCount() {
        CodingTaskPlanContextSource source = new CodingTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(
                        CodingPracticeContextAttributes.TOPIC, "数组与字符串",
                        CodingPracticeContextAttributes.DIFFICULTY, "medium",
                        CodingPracticeContextAttributes.QUESTION_TYPE, "算法题",
                        CodingPracticeContextAttributes.COUNT, 2
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("练习主题：数组与字符串"));
        assertTrue(text.contains("难度：medium"));
        assertTrue(text.contains("题型：算法题"));
        assertTrue(text.contains("数量：2"));
    }

    @Test
    void fetchFallsBackToQueryTextWhenTopicAttributeBlank() {
        CodingTaskPlanContextSource source = new CodingTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "链表",
                Map.of(CodingPracticeContextAttributes.DIFFICULTY, "easy")
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("练习主题：链表"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, AgentContextSlotFilter.none());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=CodingConstraintsContextSourceTest,CodingProfileContextSourceTest,CodingTaskPlanContextSourceTest test
```

Expected: FAIL because source classes do not exist.

- [ ] **Step 3: Implement `CodingConstraintsContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CodingConstraintsContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "coding-constraints";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.CONSTRAINTS;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.CODING_PRACTICE) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int count = CodingPracticeContextAttributes.integer(query, CodingPracticeContextAttributes.COUNT, 0);
        String questionType = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.QUESTION_TYPE);
        List<String> excludedTopics = CodingPracticeContextAttributes.stringList(query, CodingPracticeContextAttributes.EXCLUDED_TOPICS);
        if (count > 0) {
            parts.add("题目数量：" + count);
        }
        if (!questionType.isBlank()) {
            parts.add("题型：" + questionType);
        }
        if (!excludedTopics.isEmpty()) {
            parts.add("避免重复主题：" + String.join("、", excludedTopics));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 4: Implement `CodingProfileContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CodingProfileContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "coding-profile";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.PROFILE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.CODING_PRACTICE) {
            return List.of();
        }
        String snapshot = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.PROFILE_SNAPSHOT);
        if (snapshot.isBlank() || "暂无历史学习画像。".equals(snapshot)) {
            return List.of();
        }
        return List.of(new ContextItem(snapshot, 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 5: Implement `CodingTaskPlanContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CodingTaskPlanContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "coding-task-plan";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_PLAN;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.CODING_PRACTICE) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        String topic = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.TOPIC);
        if (topic.isBlank()) {
            topic = query.query();
        }
        String difficulty = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.DIFFICULTY);
        String questionType = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.QUESTION_TYPE);
        int count = CodingPracticeContextAttributes.integer(query, CodingPracticeContextAttributes.COUNT, 0);
        if (topic != null && !topic.isBlank()) {
            parts.add("练习主题：" + topic.trim());
        }
        if (!difficulty.isBlank()) {
            parts.add("难度：" + difficulty);
        }
        if (!questionType.isBlank()) {
            parts.add("题型：" + questionType);
        }
        if (count > 0) {
            parts.add("数量：" + count);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 6: Run source tests**

Run:

```bash
mvn -q -Dtest=CodingConstraintsContextSourceTest,CodingProfileContextSourceTest,CodingTaskPlanContextSourceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSource.java \
        src/main/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSource.java \
        src/main/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSource.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSourceTest.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSourceTest.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSourceTest.java
git commit -m "feat(agent): 添加刷题上下文来源"
```

---

### Task 3: Register CODING_PRACTICE schema

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`

- [ ] **Step 1: Write failing schema tests**

Append these tests to `AgentContextAssemblerTest`:

```java
    @Test
    void defaultsIncludeCodingPracticeSchema() {
        AgentContextSchema schema = AgentContextSchema.defaults().get(AgentContextMode.CODING_PRACTICE);

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
        AgentContextAssembler assembler = new AgentContextAssembler(registry);

        AgentRuntimeContext context = assembler.assemble(AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of()
        ));

        String rendered = context.render();
        assertTrue(rendered.indexOf("【硬性约束】") < rendered.indexOf("【用户画像】"));
        assertTrue(rendered.indexOf("【用户画像】") < rendered.indexOf("【任务规划】"));
    }
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: FAIL because defaults does not include `CODING_PRACTICE` and assembler falls back to knowledge QA schema.

- [ ] **Step 3: Implement schema**

Modify `AgentContextSchema.java` by adding this method after `interview()`:

```java
    public static AgentContextSchema codingPractice() {
        return new AgentContextSchema(
                AgentContextMode.CODING_PRACTICE,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(360, 3)),
                        new AgentContextSlot(AgentContextSlotKind.PROFILE, false, new AgentContextSlotFilter(360, 2)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(480, 2))
                )
        );
    }
```

Then update `defaults()` to:

```java
    public static Map<AgentContextMode, AgentContextSchema> defaults() {
        return Map.of(
                AgentContextMode.KNOWLEDGE_QA, knowledgeQa(),
                AgentContextMode.INTERVIEW, interview(),
                AgentContextMode.CODING_PRACTICE, codingPractice()
        );
    }
```

- [ ] **Step 4: Run schema tests**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java
git commit -m "feat(agent): 注册刷题上下文 schema"
```

---

### Task 4: Wire assembler into CodingPracticeService

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java`
- Modify: `src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java`
- Modify as needed: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Write failing service tests**

In `CodingPracticeServiceTest`, update imports to include:

```java
import io.github.imzmq.interview.agent.application.context.AgentContextAssembler;
import io.github.imzmq.interview.agent.application.context.AgentContextSourceRegistry;
import io.github.imzmq.interview.agent.application.context.CodingConstraintsContextSource;
import io.github.imzmq.interview.agent.application.context.CodingProfileContextSource;
import io.github.imzmq.interview.agent.application.context.CodingTaskPlanContextSource;
```

In `setUp()`, construct assembler:

```java
        AgentContextAssembler contextAssembler = new AgentContextAssembler(new AgentContextSourceRegistry(List.of(
                new CodingConstraintsContextSource(),
                new CodingProfileContextSource(),
                new CodingTaskPlanContextSource()
        )));
```

Pass `contextAssembler` as the last constructor argument when constructing `CodingPracticeService`.

Add these two tests:

```java
    @Test
    void shouldInjectAssembledContextIntoQuestionPrompt() {
        routingChatService.nextResponse = "请完成一道 Two Sum 算法题。";

        service.generateCodingQuestion("数组与字符串", "medium", "弱项：边界条件", List.of("递归"));

        assertEquals("coding-question", promptManager.lastTaskTemplateName);
        assertEquals("弱项：边界条件", promptManager.lastVariables.get("profileSnapshot"));
        String agentContext = String.valueOf(promptManager.lastVariables.get("agentContext"));
        assertTrue(agentContext.contains("【硬性约束】"));
        assertTrue(agentContext.contains("避免重复主题：递归"));
        assertTrue(agentContext.contains("【用户画像】"));
        assertTrue(agentContext.contains("弱项：边界条件"));
        assertTrue(agentContext.contains("【任务规划】"));
        assertTrue(agentContext.contains("练习主题：数组与字符串"));
    }

    @Test
    void shouldInjectAssembledContextIntoBatchQuizPrompt() {
        routingChatService.nextResponse = "[{\"index\":1,\"stem\":\"题干\",\"options\":[\"A. a\",\"B. b\",\"C. c\",\"D. d\"],\"correctAnswer\":\"A\",\"explanation\":\"解析\"}]";

        service.generateBatchQuiz("Java基础", "easy", 2, "弱项：集合", List.of("线程"));

        assertEquals("batch-quiz-question", promptManager.lastTaskTemplateName);
        assertEquals("弱项：集合", promptManager.lastVariables.get("profileSnapshot"));
        String agentContext = String.valueOf(promptManager.lastVariables.get("agentContext"));
        assertTrue(agentContext.contains("题目数量：2"));
        assertTrue(agentContext.contains("题型：选择题"));
        assertTrue(agentContext.contains("避免重复主题：线程"));
        assertTrue(agentContext.contains("练习主题：Java基础"));
    }
```

- [ ] **Step 2: Run service test to verify failure**

Run:

```bash
mvn -q -Dtest=CodingPracticeServiceTest test
```

Expected: FAIL because `CodingPracticeService` constructor has no assembler parameter and prompt variables do not contain `agentContext`.

- [ ] **Step 3: Modify constructor and fields**

In `CodingPracticeService.java`, add imports:

```java
import io.github.imzmq.interview.agent.application.context.AgentContextAssembler;
import io.github.imzmq.interview.agent.application.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.context.CodingPracticeContextAttributes;
```

Add field:

```java
    private final AgentContextAssembler contextAssembler;
```

Update constructor signature and assignment:

```java
    public CodingPracticeService(RoutingChatService routingChatService,
                                 AgentSkillService agentSkillService,
                                 PromptManager promptManager,
                                 SkillOrchestrator skillOrchestrator,
                                 LlmJsonParser llmJsonParser,
                                 AgentContextAssembler contextAssembler) {
        this.routingChatService = routingChatService;
        this.agentSkillService = agentSkillService;
        this.promptManager = promptManager;
        this.skillOrchestrator = skillOrchestrator;
        this.llmJsonParser = llmJsonParser;
        this.contextAssembler = contextAssembler;
    }
```

- [ ] **Step 4: Add helper method for prompt context**

Add this private method in `CodingPracticeService`:

```java
    private String assembleCodingContext(String topic,
                                         String difficulty,
                                         String questionType,
                                         int count,
                                         String profileSnapshot,
                                         List<String> excludedTopics) {
        if (contextAssembler == null) {
            return "";
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CodingPracticeContextAttributes.TOPIC, topic == null ? "" : topic);
        attributes.put(CodingPracticeContextAttributes.DIFFICULTY, difficulty == null ? "" : difficulty);
        attributes.put(CodingPracticeContextAttributes.QUESTION_TYPE, questionType == null ? "" : questionType);
        attributes.put(CodingPracticeContextAttributes.COUNT, count);
        attributes.put(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, profileSnapshot == null ? "" : profileSnapshot);
        attributes.put(CodingPracticeContextAttributes.EXCLUDED_TOPICS, excludedTopics == null ? List.of() : excludedTopics);
        return contextAssembler.assemble(AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                topic == null ? "" : topic,
                attributes
        )).render();
    }
```

- [ ] **Step 5: Add `agentContext` in `generateCodingQuestion`**

Before `PromptManager.PromptPair pair = ...`, compute:

```java
        String agentContext = assembleCodingContext(
                normalizedTopic,
                normalizedDifficulty,
                normalizedQuestionType,
                1,
                profileSnapshot,
                excludedTopics
        );
```

Then add to params:

```java
        params.put("agentContext", agentContext);
```

Keep existing params unchanged.

- [ ] **Step 6: Add `agentContext` in `generateBatchQuiz`**

Before rendering `batch-quiz-question`, compute:

```java
        int safeCount = Math.min(count, 10);
        String agentContext = assembleCodingContext(
                normalizedTopic,
                normalizedDifficulty,
                "选择题",
                safeCount,
                profileSnapshot,
                excludedTopics
        );
```

Change the existing count param to use `safeCount`:

```java
        params.put("count", String.valueOf(safeCount));
        params.put("agentContext", agentContext);
```

Keep fallback behavior unchanged.

- [ ] **Step 7: Update tests that construct `CodingPracticeService` directly**

Search:

```bash
grep -R "new CodingPracticeService" -n src/test/java src/main/java
```

For every test constructor call, add an assembler like:

```java
new AgentContextAssembler(new AgentContextSourceRegistry(List.of(
        new CodingConstraintsContextSource(),
        new CodingProfileContextSource(),
        new CodingTaskPlanContextSource()
)))
```

Do not add this manual construction in production Spring config; Spring should inject the real bean automatically.

- [ ] **Step 8: Run service tests**

Run:

```bash
mvn -q -Dtest=CodingPracticeServiceTest test
```

Expected: PASS.

- [ ] **Step 9: Run RAG service tests affected by constructor wiring**

Run:

```bash
mvn -q -Dtest=RAGServiceTest test
```

Expected: PASS. If compilation fails due to constructor changes, update only test setup as described in Step 7 and rerun.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java \
        src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java \
        src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor(coding): 接入刷题上下文装配"
```

---

### Task 5: Final verification and docs check

**Files:**
- Read-only check: `PACKAGE_CONVENTIONS.md`
- Read-only check: `docs/superpowers/specs/2026-06-30-coding-practice-context-assembly-design.md`
- Optional modify only if implementation changed scope: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Run targeted tests**

Run:

```bash
mvn -q -Dtest=CodingPracticeContextAttributesTest,CodingConstraintsContextSourceTest,CodingProfileContextSourceTest,CodingTaskPlanContextSourceTest,AgentContextAssemblerTest,CodingPracticeServiceTest test
```

Expected: PASS.

- [ ] **Step 2: Run architecture guardrail**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS.

- [ ] **Step 3: Run compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: PASS.

- [ ] **Step 4: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 5: Verify AGENTS.md is not accidentally staged**

Run:

```bash
git status --short
```

Expected: `AGENTS.md` may appear as unstaged modified due to auto-injected local context, but it must not be staged or committed.

- [ ] **Step 6: Final commit if docs changed during implementation**

If `PACKAGE_CONVENTIONS.md` or other docs were intentionally updated, commit them separately:

```bash
git add PACKAGE_CONVENTIONS.md
git commit -m "docs: 更新刷题上下文装配规范"
```

If no docs changed, do not create an empty commit.
