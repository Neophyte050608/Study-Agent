# Interview Context Assembly Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route interview answer evaluation prompt context through the existing Agent context assembly module while preserving current prompt variables and evidence validation behavior.

**Architecture:** Extend `agent.application.context` with interview-specific attributes and sources, add an `INTERVIEW` schema, then inject `AgentContextAssembler` into `InterviewAnswerEvaluationService`. The assembler consumes already-computed profile, strategy, and `RAGService.KnowledgePacket` snapshots; it does not call RAG, LLM, database, or external services.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven, existing `AgentContextAssembler`, `InterviewAnswerEvaluationService`, `RAGService.KnowledgePacket`.

---

## File Structure

Create interview context files:

- `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewContextAttributes.java` — typed attribute keys and readers for interview evaluation context.
- `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewProfileContextSource.java` — fills `PROFILE` from a precomputed profile snapshot.
- `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewStrategyContextSource.java` — fills `TASK_PLAN` from difficulty, follow-up state, mastery, stage, round, and strategy hint.
- `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewKnowledgeContextSource.java` — fills `KNOWLEDGE` from `RAGService.KnowledgePacket` without Knowledge QA semantics.

Modify existing files:

- `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java` — add `INTERVIEW` schema to defaults.
- `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/InterviewAnswerEvaluationService.java` — inject `AgentContextAssembler`, assemble interview context, pass rendered context into prompt variables.

Create tests:

- `src/test/java/io/github/imzmq/interview/agent/application/context/InterviewProfileContextSourceTest.java`
- `src/test/java/io/github/imzmq/interview/agent/application/context/InterviewStrategyContextSourceTest.java`
- `src/test/java/io/github/imzmq/interview/agent/application/context/InterviewKnowledgeContextSourceTest.java`
- Extend `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`

---

### Task 1: Add interview context attributes and profile source

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewContextAttributes.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewProfileContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/InterviewProfileContextSourceTest.java`

- [ ] **Step 1: Write failing profile source test**

Create `InterviewProfileContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewProfileContextSourceTest {

    @Test
    void fetchReturnsEmptyWhenProfileSnapshotBlankOrDefault() {
        InterviewProfileContextSource source = new InterviewProfileContextSource();

        List<ContextItem> blankItems = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.PROFILE_SNAPSHOT, " ")
        ));
        List<ContextItem> defaultItems = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.PROFILE_SNAPSHOT, "暂无历史学习画像。")
        ));

        assertTrue(blankItems.isEmpty());
        assertTrue(defaultItems.isEmpty());
    }

    @Test
    void fetchReturnsProfileSnapshotItem() {
        InterviewProfileContextSource source = new InterviewProfileContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.PROFILE_SNAPSHOT, "弱项：JVM；熟项：Spring")
        ));

        assertEquals(1, items.size());
        assertEquals("弱项：JVM；熟项：Spring", items.get(0).text());
        assertEquals("interview-profile", items.get(0).source());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.PROFILE, false, AgentContextSlotFilter.none());
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -q -Dtest=InterviewProfileContextSourceTest test
```

Expected: fail because `InterviewContextAttributes` and `InterviewProfileContextSource` do not exist.

- [ ] **Step 3: Add attribute helper**

Create `InterviewContextAttributes.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.application.RAGService;

public final class InterviewContextAttributes {
    public static final String PROFILE_SNAPSHOT = "interview.profileSnapshot";
    public static final String STRATEGY_HINT = "interview.strategyHint";
    public static final String TOPIC = "interview.topic";
    public static final String QUESTION = "interview.question";
    public static final String USER_ANSWER = "interview.userAnswer";
    public static final String DIFFICULTY_LEVEL = "interview.difficultyLevel";
    public static final String FOLLOW_UP_STATE = "interview.followUpState";
    public static final String TOPIC_MASTERY = "interview.topicMastery";
    public static final String KNOWLEDGE_PACKET = "interview.knowledgePacket";
    public static final String CURRENT_STAGE = "interview.currentStage";
    public static final String NEXT_STAGE = "interview.nextStage";
    public static final String ANSWERED_COUNT = "interview.answeredCount";
    public static final String TOTAL_QUESTIONS = "interview.totalQuestions";

    private InterviewContextAttributes() {
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static double number(AgentContextQuery query, String key, double defaultValue) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static RAGService.KnowledgePacket packet(AgentContextQuery query) {
        Object value = query == null ? null : query.attribute(KNOWLEDGE_PACKET);
        return value instanceof RAGService.KnowledgePacket packet ? packet : null;
    }
}
```

- [ ] **Step 4: Add profile source**

Create `InterviewProfileContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InterviewProfileContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "interview-profile";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.PROFILE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.INTERVIEW) {
            return List.of();
        }
        String profile = InterviewContextAttributes.text(query, InterviewContextAttributes.PROFILE_SNAPSHOT);
        if (profile.isBlank() || "暂无历史学习画像。".equals(profile)) {
            return List.of();
        }
        return List.of(new ContextItem(profile, 1.0, id(), Map.of("kind", "profileSnapshot")));
    }
}
```

- [ ] **Step 5: Verify test passes**

Run:

```bash
mvn -q -Dtest=InterviewProfileContextSourceTest test
```

Expected: pass.

- [ ] **Step 6: Commit task 1**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/InterviewContextAttributes.java \
  src/main/java/io/github/imzmq/interview/agent/application/context/InterviewProfileContextSource.java \
  src/test/java/io/github/imzmq/interview/agent/application/context/InterviewProfileContextSourceTest.java
git commit -m "feat(agent): 添加面试画像上下文来源"
```

---

### Task 2: Add interview strategy source

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewStrategyContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/InterviewStrategyContextSourceTest.java`

- [ ] **Step 1: Write failing strategy source tests**

Create `InterviewStrategyContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewStrategyContextSourceTest {

    @Test
    void fetchRendersDifficultyFollowUpMasteryAndStrategy() {
        InterviewStrategyContextSource source = new InterviewStrategyContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(
                        InterviewContextAttributes.DIFFICULTY_LEVEL, "ADVANCED",
                        InterviewContextAttributes.FOLLOW_UP_STATE, "PROBE",
                        InterviewContextAttributes.TOPIC_MASTERY, 62.0,
                        InterviewContextAttributes.STRATEGY_HINT, "保持中等强度评估，兼顾理论与实践。"
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("当前难度：ADVANCED"));
        assertTrue(text.contains("追问状态：PROBE"));
        assertTrue(text.contains("主题掌握度：62.0"));
        assertTrue(text.contains("评估策略：保持中等强度评估"));
    }

    @Test
    void fetchReturnsEmptyForNonInterviewMode() {
        InterviewStrategyContextSource source = new InterviewStrategyContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of(InterviewContextAttributes.STRATEGY_HINT, "策略")
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, AgentContextSlotFilter.none());
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -q -Dtest=InterviewStrategyContextSourceTest test
```

Expected: fail because `InterviewStrategyContextSource` does not exist.

- [ ] **Step 3: Add strategy source**

Create `InterviewStrategyContextSource.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class InterviewStrategyContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "interview-strategy";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_PLAN;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.INTERVIEW) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        String difficulty = InterviewContextAttributes.text(query, InterviewContextAttributes.DIFFICULTY_LEVEL);
        String followUp = InterviewContextAttributes.text(query, InterviewContextAttributes.FOLLOW_UP_STATE);
        double mastery = InterviewContextAttributes.number(query, InterviewContextAttributes.TOPIC_MASTERY, -1.0);
        String currentStage = InterviewContextAttributes.text(query, InterviewContextAttributes.CURRENT_STAGE);
        String nextStage = InterviewContextAttributes.text(query, InterviewContextAttributes.NEXT_STAGE);
        String strategy = InterviewContextAttributes.text(query, InterviewContextAttributes.STRATEGY_HINT);

        if (!difficulty.isBlank()) {
            parts.add("当前难度：" + difficulty);
        }
        if (!followUp.isBlank()) {
            parts.add("追问状态：" + followUp);
        }
        if (mastery >= 0.0) {
            parts.add("主题掌握度：" + String.format("%.1f", mastery));
        }
        if (!currentStage.isBlank()) {
            parts.add("当前阶段：" + currentStage);
        }
        if (!nextStage.isBlank()) {
            parts.add("下一阶段：" + nextStage);
        }
        if (!strategy.isBlank()) {
            parts.add("评估策略：" + strategy);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
```

- [ ] **Step 4: Verify test passes**

Run:

```bash
mvn -q -Dtest=InterviewStrategyContextSourceTest test
```

Expected: pass.

- [ ] **Step 5: Commit task 2**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/InterviewStrategyContextSource.java \
  src/test/java/io/github/imzmq/interview/agent/application/context/InterviewStrategyContextSourceTest.java
git commit -m "feat(agent): 添加面试策略上下文来源"
```

---

### Task 3: Add interview knowledge source

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/InterviewKnowledgeContextSource.java`
- Test: `src/test/java/io/github/imzmq/interview/agent/application/context/InterviewKnowledgeContextSourceTest.java`

- [ ] **Step 1: Write failing knowledge source tests**

Create `InterviewKnowledgeContextSourceTest.java`:

```java
package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewKnowledgeContextSourceTest {

    @Test
    void fetchReturnsEmptyWhenPacketMissing() {
        InterviewKnowledgeContextSource source = new InterviewKnowledgeContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of()
        ));

        assertTrue(items.isEmpty());
    }

    @Test
    void fetchRendersPacketContextImageAndEvidenceSummary() {
        InterviewKnowledgeContextSource source = new InterviewKnowledgeContextSource();
        RAGService.KnowledgePacket packet = new RAGService.KnowledgePacket(
                "full query",
                List.of(new Document("文档证据内容")),
                List.of(),
                "RAG 文本上下文",
                "图片上下文",
                "[1] 文档证据内容",
                false
        );

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.KNOWLEDGE_PACKET, packet)
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("RAG 文本上下文"));
        assertTrue(text.contains("图片上下文"));
        assertTrue(text.contains("证据目录摘要"));
        assertTrue(text.contains("[1] 文档证据内容"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, AgentContextSlotFilter.none());
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -q -Dtest=InterviewKnowledgeContextSourceTest test
```

Expected: fail because `InterviewKnowledgeContextSource` does not exist.

- [ ] **Step 3: Add knowledge source**

Create `InterviewKnowledgeContextSource.java`:

```java
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
```

- [ ] **Step 4: Verify test passes**

Run:

```bash
mvn -q -Dtest=InterviewKnowledgeContextSourceTest test
```

Expected: pass.

- [ ] **Step 5: Commit task 3**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/InterviewKnowledgeContextSource.java \
  src/test/java/io/github/imzmq/interview/agent/application/context/InterviewKnowledgeContextSourceTest.java
git commit -m "feat(agent): 添加面试知识上下文来源"
```

---

### Task 4: Add INTERVIEW schema

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`

- [ ] **Step 1: Add failing schema test**

Append this test to `AgentContextAssemblerTest`:

```java
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
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: fail because `AgentContextSchema.defaults()` has no `INTERVIEW` schema and falls back to Knowledge QA order.

- [ ] **Step 3: Add interview schema**

Modify `AgentContextSchema.java` to add:

```java
    public static AgentContextSchema interview() {
        return new AgentContextSchema(
                AgentContextMode.INTERVIEW,
                List.of(
                        new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, new AgentContextSlotFilter(240, 4)),
                        new AgentContextSlot(AgentContextSlotKind.PROFILE, false, new AgentContextSlotFilter(480, 2)),
                        new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, new AgentContextSlotFilter(700, 3)),
                        new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, new AgentContextSlotFilter(2200, 3))
                )
        );
    }
```

Then replace `defaults()` with:

```java
    public static Map<AgentContextMode, AgentContextSchema> defaults() {
        return Map.of(
                AgentContextMode.KNOWLEDGE_QA, knowledgeQa(),
                AgentContextMode.INTERVIEW, interview()
        );
    }
```

- [ ] **Step 4: Verify assembler tests pass**

Run:

```bash
mvn -q -Dtest=AgentContextAssemblerTest test
```

Expected: pass.

- [ ] **Step 5: Commit task 4**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextSchema.java \
  src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java
git commit -m "feat(agent): 添加面试上下文装配 schema"
```

---

### Task 5: Integrate assembler into InterviewAnswerEvaluationService

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/InterviewAnswerEvaluationService.java`

- [ ] **Step 1: Add imports and constructor dependency**

In `InterviewAnswerEvaluationService.java`, add imports:

```java
import io.github.imzmq.interview.agent.application.context.AgentContextAssembler;
import io.github.imzmq.interview.agent.application.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.context.AgentRuntimeContext;
import io.github.imzmq.interview.agent.application.context.InterviewContextAttributes;
```

Add field:

```java
    private final AgentContextAssembler contextAssembler;
```

Update constructor signature from:

```java
                                            ObservabilitySwitchProperties observabilitySwitchProperties,
                                            SkillOrchestrator skillOrchestrator,
                                            LlmJsonParser llmJsonParser) {
```

to:

```java
                                            ObservabilitySwitchProperties observabilitySwitchProperties,
                                            SkillOrchestrator skillOrchestrator,
                                            LlmJsonParser llmJsonParser,
                                            AgentContextAssembler contextAssembler) {
```

Assign field:

```java
        this.contextAssembler = contextAssembler;
```

- [ ] **Step 2: Add private helper to assemble context**

Add this private method near `generateEvaluationResult`:

```java
    private String assembleInterviewContext(String topic,
                                            String question,
                                            String userAnswer,
                                            String difficultyLevel,
                                            String followUpState,
                                            double topicMastery,
                                            String profileSnapshot,
                                            String strategyHint,
                                            RAGService.KnowledgePacket packet,
                                            String fallbackContext) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(InterviewContextAttributes.TOPIC, topic == null ? "" : topic);
        attributes.put(InterviewContextAttributes.QUESTION, question == null ? "" : question);
        attributes.put(InterviewContextAttributes.USER_ANSWER, userAnswer == null ? "" : userAnswer);
        attributes.put(InterviewContextAttributes.DIFFICULTY_LEVEL, difficultyLevel == null ? "" : difficultyLevel);
        attributes.put(InterviewContextAttributes.FOLLOW_UP_STATE, followUpState == null ? "" : followUpState);
        attributes.put(InterviewContextAttributes.TOPIC_MASTERY, topicMastery);
        attributes.put(InterviewContextAttributes.PROFILE_SNAPSHOT, profileSnapshot == null ? "" : profileSnapshot);
        attributes.put(InterviewContextAttributes.STRATEGY_HINT, strategyHint == null ? "" : strategyHint);
        if (packet != null) {
            attributes.put(InterviewContextAttributes.KNOWLEDGE_PACKET, packet);
        }
        AgentRuntimeContext runtimeContext = contextAssembler.assemble(AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                question,
                attributes
        ));
        String rendered = runtimeContext.render();
        return rendered.isBlank() ? fallbackContext : rendered;
    }
```

- [ ] **Step 3: Use assembled context before LLM call**

In `evaluateWithKnowledge`, after `normalizedStrategy` is resolved and before logging/call, add:

```java
            String assembledContext = assembleInterviewContext(
                    topic,
                    question,
                    userAnswer,
                    difficultyLevel,
                    followUpState,
                    topicMastery,
                    safeProfileSnapshot,
                    normalizedStrategy,
                    packet,
                    finalContext
            );
```

Then replace the `generateEvaluationResult` call argument from `finalContext` to `assembledContext`:

```java
() -> generateEvaluationResult(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, safeProfileSnapshot, assembledContext, finalImageContext, finalEvidence, effectiveStrategyHint)
```

- [ ] **Step 4: Add safe log fields for assembled context length**

In the existing RAG trace log format, add fields at the end:

```java
, assembledContextLen={}
```

and pass:

```java
safeLength(assembledContext)
```

Do not log the full assembled context.

- [ ] **Step 5: Run focused tests and compile**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest,AgentContextAssemblerTest,InterviewProfileContextSourceTest,InterviewStrategyContextSourceTest,InterviewKnowledgeContextSourceTest test
mvn -q -DskipTests compile
```

Expected: both pass.

- [ ] **Step 6: Commit task 5**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/InterviewAnswerEvaluationService.java
git commit -m "refactor(interview): 接入评估上下文装配"
```

---

### Task 6: Final verification

**Files:**
- No intended production edits beyond prior tasks.

- [ ] **Step 1: Remove injected local memory block if present**

Run:

```bash
grep -n "claude-mem-context" AGENTS.md || true
git diff -- AGENTS.md
```

Expected: no `claude-mem-context` output and no AGENTS.md diff. If present, remove the entire `<claude-mem-context>...</claude-mem-context>` block before continuing.

- [ ] **Step 2: Run focused context tests**

Run:

```bash
mvn -q -Dtest=AgentRuntimeContextTest,AgentContextAssemblerTest,InterviewProfileContextSourceTest,InterviewStrategyContextSourceTest,InterviewKnowledgeContextSourceTest test
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

- [ ] **Step 5: Run diff/status checks**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: `git diff --check` has no output. Status should show only committed changes ahead of origin and no unstaged source changes.

- [ ] **Step 6: Summarize**

Report:

```text
完成：新增 INTERVIEW schema、面试画像/策略/知识 source，并接入 InterviewAnswerEvaluationService。
验证：列出本轮实际运行且退出码为 0 的 Maven/Git 命令。
未做：首题生成、最终报告、刷题链路未迁移。
```
