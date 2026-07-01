# TOOL_TASK Context Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `AgentContextMode.TOOL_TASK` 增加 schema、属性约定和基础上下文 source，让未来任务型 Agent 可以稳定装配安全约束、任务计划、工具状态和任务记忆。

**Architecture:** 继续复用 `agent.application.context` 的 schema/source/assembler 模型，只新增纯上下文组件，不实现真实工具执行器、不读外部状态、不改变现有业务调用点。所有 source 只从 `AgentContextQuery.attributes` 读取已脱敏摘要，并且必须限定 `TOOL_TASK` mode。

**Tech Stack:** Java 21, Spring Boot component scanning, JUnit 5, Maven, existing `AgentContextAssembler`.

---

## File Structure

- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributes.java`
  - 定义 TOOL_TASK 属性 key，并提供 `text()`、`stringList()` helper。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskConstraintsContextSource.java`
  - 渲染 `safetyRules` 和 `confirmationPolicy` 到 `CONSTRAINTS` slot。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskPlanContextSource.java`
  - 渲染 `taskGoal`、`userRequest` 和 `taskPlan` 到 `TASK_PLAN` slot。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolStateContextSource.java`
  - 渲染 `availableTools`、`disabledTools` 和 `lastToolResult` 到 `TOOL_STATE` slot。
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskMemoryContextSource.java`
  - 渲染 `completedSteps` 和 `observations` 到 `TASK_MEMORY` slot。
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java`
  - 新增 `toolTask()`，并注册到 `defaults()`。
- Create tests under `src/test/java/io/github/imzmq/interview/agent/application/context/`
  - 覆盖 attributes、四个 source 和 schema 顺序。
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`
  - 增加 `TOOL_TASK` schema 注册和顺序测试。

---

### Task 1: Add TOOL_TASK attribute helper

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributes.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributesTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributesTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskContextAttributesTest {

    @Test
    void textReturnsTrimmedValue() {
        AgentContextQuery query = AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(ToolTaskContextAttributes.TASK_GOAL, "  清理死代码  ")
        );

        assertEquals("清理死代码", ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.TASK_GOAL));
    }

    @Test
    void stringListReadsListAndSingleString() {
        AgentContextQuery listQuery = AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(ToolTaskContextAttributes.TASK_PLAN, List.of("检查引用", " 删除空项 ", ""))
        );
        AgentContextQuery stringQuery = AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(ToolTaskContextAttributes.SAFETY_RULES, " 不删除用户文件 ")
        );

        assertEquals(List.of("检查引用", "删除空项"), ToolTaskContextAttributes.stringList(listQuery, ToolTaskContextAttributes.TASK_PLAN));
        assertEquals(List.of("不删除用户文件"), ToolTaskContextAttributes.stringList(stringQuery, ToolTaskContextAttributes.SAFETY_RULES));
        assertTrue(ToolTaskContextAttributes.stringList(null, ToolTaskContextAttributes.TASK_PLAN).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=ToolTaskContextAttributesTest test
```

Expected: FAIL because `ToolTaskContextAttributes` does not exist.

- [ ] **Step 3: Implement attribute helper**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributes.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public final class ToolTaskContextAttributes {
    public static final String TASK_GOAL = "toolTask.taskGoal";
    public static final String USER_REQUEST = "toolTask.userRequest";
    public static final String TASK_PLAN = "toolTask.taskPlan";
    public static final String COMPLETED_STEPS = "toolTask.completedSteps";
    public static final String OBSERVATIONS = "toolTask.observations";
    public static final String AVAILABLE_TOOLS = "toolTask.availableTools";
    public static final String DISABLED_TOOLS = "toolTask.disabledTools";
    public static final String LAST_TOOL_RESULT = "toolTask.lastToolResult";
    public static final String CONFIRMATION_POLICY = "toolTask.confirmationPolicy";
    public static final String SAFETY_RULES = "toolTask.safetyRules";

    private ToolTaskContextAttributes() {
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
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
mvn -q -Dtest=ToolTaskContextAttributesTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributes.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskContextAttributesTest.java
git commit -m "feat(agent): 添加任务型上下文属性"
```

---

### Task 2: Add TOOL_TASK context sources

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskConstraintsContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskPlanContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolStateContextSource.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskMemoryContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskConstraintsContextSourceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskPlanContextSourceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/ToolStateContextSourceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskMemoryContextSourceTest.java`

- [ ] **Step 1: Write failing source tests**

Create `ToolTaskConstraintsContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskConstraintsContextSourceTest {

    @Test
    void fetchRendersSafetyRulesAndConfirmationPolicy() {
        ToolTaskConstraintsContextSource source = new ToolTaskConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(
                        ToolTaskContextAttributes.SAFETY_RULES, List.of("禁止删除用户文件", "禁止提交密钥"),
                        ToolTaskContextAttributes.CONFIRMATION_POLICY, "删除文件前必须确认"
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("安全规则：禁止删除用户文件、禁止提交密钥"));
        assertTrue(text.contains("确认策略：删除文件前必须确认"));
    }

    @Test
    void fetchReturnsEmptyForNonToolTaskMode() {
        ToolTaskConstraintsContextSource source = new ToolTaskConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of(ToolTaskContextAttributes.CONFIRMATION_POLICY, "确认")
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, AgentContextSlotFilter.none());
    }
}
```

Create `ToolTaskPlanContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskPlanContextSourceTest {

    @Test
    void fetchRendersGoalRequestAndPlan() {
        ToolTaskPlanContextSource source = new ToolTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "清理死代码",
                Map.of(
                        ToolTaskContextAttributes.TASK_GOAL, "清理死代码",
                        ToolTaskContextAttributes.USER_REQUEST, "先找不用的模块",
                        ToolTaskContextAttributes.TASK_PLAN, List.of("扫描引用", "移除无用类")
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("任务目标：清理死代码"));
        assertTrue(text.contains("用户请求：先找不用的模块"));
        assertTrue(text.contains("计划步骤：扫描引用、移除无用类"));
    }

    @Test
    void fetchFallsBackToQueryWhenGoalBlank() {
        ToolTaskPlanContextSource source = new ToolTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理模块结构",
                Map.of(ToolTaskContextAttributes.TASK_PLAN, "检查包结构")
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("任务目标：整理模块结构"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, AgentContextSlotFilter.none());
    }
}
```

Create `ToolStateContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolStateContextSourceTest {

    @Test
    void fetchRendersAvailableDisabledToolsAndLastResult() {
        ToolStateContextSource source = new ToolStateContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(
                        ToolTaskContextAttributes.AVAILABLE_TOOLS, List.of("read_file", "run_tests"),
                        ToolTaskContextAttributes.DISABLED_TOOLS, "delete_file",
                        ToolTaskContextAttributes.LAST_TOOL_RESULT, "测试通过 12 个"
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("可用工具：read_file、run_tests"));
        assertTrue(text.contains("禁用工具：delete_file"));
        assertTrue(text.contains("最近工具结果：测试通过 12 个"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TOOL_STATE, false, AgentContextSlotFilter.none());
    }
}
```

Create `ToolTaskMemoryContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskMemoryContextSourceTest {

    @Test
    void fetchRendersCompletedStepsAndObservations() {
        ToolTaskMemoryContextSource source = new ToolTaskMemoryContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(
                        ToolTaskContextAttributes.COMPLETED_STEPS, List.of("读取规范", "检查目录"),
                        ToolTaskContextAttributes.OBSERVATIONS, List.of("发现旧 service 包", "AGENTS.md 有本地注入")
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("已完成步骤：读取规范、检查目录"));
        assertTrue(text.contains("执行观察：发现旧 service 包、AGENTS.md 有本地注入"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_MEMORY, false, AgentContextSlotFilter.none());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=ToolTaskConstraintsContextSourceTest,ToolTaskPlanContextSourceTest,ToolStateContextSourceTest,ToolTaskMemoryContextSourceTest test
```

Expected: FAIL because source classes do not exist.

- [ ] **Step 3: Implement `ToolTaskConstraintsContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskConstraintsContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolTaskConstraintsContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-task-constraints";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.CONSTRAINTS;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        List<String> safetyRules = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.SAFETY_RULES);
        String confirmationPolicy = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.CONFIRMATION_POLICY);
        if (!safetyRules.isEmpty()) {
            parts.add("安全规则：" + String.join("、", safetyRules));
        }
        if (!confirmationPolicy.isBlank()) {
            parts.add("确认策略：" + confirmationPolicy);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 4: Implement `ToolTaskPlanContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskPlanContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolTaskPlanContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-task-plan";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_PLAN;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        String taskGoal = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.TASK_GOAL);
        if (taskGoal.isBlank()) {
            taskGoal = query.query();
        }
        String userRequest = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.USER_REQUEST);
        List<String> taskPlan = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.TASK_PLAN);
        if (taskGoal != null && !taskGoal.isBlank()) {
            parts.add("任务目标：" + taskGoal.trim());
        }
        if (!userRequest.isBlank()) {
            parts.add("用户请求：" + userRequest);
        }
        if (!taskPlan.isEmpty()) {
            parts.add("计划步骤：" + String.join("、", taskPlan));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 5: Implement `ToolStateContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/ToolStateContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolStateContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-state";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TOOL_STATE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        List<String> availableTools = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.AVAILABLE_TOOLS);
        List<String> disabledTools = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.DISABLED_TOOLS);
        String lastToolResult = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.LAST_TOOL_RESULT);
        if (!availableTools.isEmpty()) {
            parts.add("可用工具：" + String.join("、", availableTools));
        }
        if (!disabledTools.isEmpty()) {
            parts.add("禁用工具：" + String.join("、", disabledTools));
        }
        if (!lastToolResult.isBlank()) {
            parts.add("最近工具结果：" + lastToolResult);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 6: Implement `ToolTaskMemoryContextSource`**

Create `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskMemoryContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolTaskMemoryContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-task-memory";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_MEMORY;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        List<String> completedSteps = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.COMPLETED_STEPS);
        List<String> observations = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.OBSERVATIONS);
        if (!completedSteps.isEmpty()) {
            parts.add("已完成步骤：" + String.join("、", completedSteps));
        }
        if (!observations.isEmpty()) {
            parts.add("执行观察：" + String.join("、", observations));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 7: Run source tests**

Run:

```bash
mvn -q -Dtest=ToolTaskConstraintsContextSourceTest,ToolTaskPlanContextSourceTest,ToolStateContextSourceTest,ToolTaskMemoryContextSourceTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskConstraintsContextSource.java \
        src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskPlanContextSource.java \
        src/main/java/io/github/imzmq/interview/agent/application/context/ToolStateContextSource.java \
        src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskMemoryContextSource.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskConstraintsContextSourceTest.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskPlanContextSourceTest.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/ToolStateContextSourceTest.java \
        src/test/java/io/github/imzmq/interview/agent/application/context/ToolTaskMemoryContextSourceTest.java
git commit -m "feat(agent): 添加任务型上下文来源"
```

---

### Task 3: Register TOOL_TASK schema

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`

- [ ] **Step 1: Write failing schema tests**

Append these tests to `AgentContextAssemblerTest` before the helper methods:

```java
    @Test
    void defaultsIncludeToolTaskSchema() {
        AgentContextSchema schema = AgentContextSchema.defaults().get(AgentContextMode.TOOL_TASK);

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
        AgentContextAssembler assembler = new AgentContextAssembler(registry);

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
```

- [ ] **Step 2: Run schema test to verify failure**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: FAIL because `TOOL_TASK` is not registered in defaults.

- [ ] **Step 3: Implement `toolTask()` schema**

Modify `AgentContextSchema.java` by adding this method after `codingPractice()`:

```java
    public static AgentContextSchema toolTask() {
        return new AgentContextSchema(
                AgentContextMode.TOOL_TASK,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(600, 3)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(900, 3)),
                        new AgentContextSlot(AgentContextSlotKind.TOOL_STATE, false, new AgentContextSlotFilter(700, 3)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_MEMORY, false, new AgentContextSlotFilter(1200, 4))
                )
        );
    }
```

Update `defaults()` to include:

```java
                AgentContextMode.CODING_PRACTICE, codingPractice(),
                AgentContextMode.TOOL_TASK, toolTask()
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
git commit -m "feat(agent): 注册任务型上下文 schema"
```

---

### Task 4: Final verification

**Files:**
- Read-only check: `PACKAGE_CONVENTIONS.md`
- Read-only check: `docs/superpowers/specs/2026-07-01-tool-task-context-foundation-design.md`

- [ ] **Step 1: Run targeted tests**

Run:

```bash
mvn -q -Dtest=ToolTaskContextAttributesTest,ToolTaskConstraintsContextSourceTest,ToolTaskPlanContextSourceTest,ToolStateContextSourceTest,ToolTaskMemoryContextSourceTest,AgentContextAssemblerTest test
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

- [ ] **Step 5: Verify only expected uncommitted file remains**

Run:

```bash
git status --short
```

Expected: `AGENTS.md` may appear as unstaged modified due to local injected context. It must not be staged or committed.
