# Framework Adoption Phase 1 Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first incremental framework-adoption step by introducing a framework-neutral LLM/RAG observability port that can later connect to Langfuse/OpenTelemetry without coupling business code to external SDKs.

**Architecture:** Keep existing `RAGObservabilityService` and SSE/database trace behavior as the source of local trace data. Add a small `observability.application` event port and a no-op/default logging adapter, then emit sanitized events from RAG trace and model-routing boundaries. Do not add Langfuse, OpenTelemetry, Temporal, or Mastra dependencies in this phase.

**Tech Stack:** Java 17, Spring Boot, Spring AI existing model stack, JUnit 5, Maven, existing `TraceAttributeSanitizer`, existing `RAGObservabilityService`.

---

## Scope

This plan implements only Phase 1 foundation from `docs/architecture/framework-adoption-roadmap.md`:

- Create a stable internal observability port for LLM/RAG/Agent events.
- Emit sanitized RAG node and model-routing events into that port.
- Add tests proving sanitization, no-op safety, and event publication.
- Document how Langfuse/OpenTelemetry adapters should attach later.

Out of scope:

- Adding Langfuse SDK dependency.
- Adding OpenTelemetry Java agent or starter.
- Replacing existing RAG trace tables or frontend trace pages.
- Changing model-routing behavior.
- Adding Temporal or Mastra.

## File Structure

Create:

- `src/main/java/io/github/imzmq/interview/observability/application/AiObservationEvent.java`
  Immutable event contract used inside the application.
- `src/main/java/io/github/imzmq/interview/observability/application/AiObservationPublisher.java`
  Framework-neutral port for publishing sanitized AI events.
- `src/main/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisher.java`
  Default safe publisher; logs at debug and never fails business flow.
- `src/test/java/io/github/imzmq/interview/observability/application/AiObservationEventTest.java`
  Unit tests for event factory and immutable attributes.
- `src/test/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisherTest.java`
  Unit tests proving no-op publisher does not throw.
- `src/test/java/io/github/imzmq/interview/observability/application/RecordingAiObservationPublisher.java`
  Test helper publisher for interaction tests.
- `docs/development/observability-guidelines.md`
  Developer guide for current trace port and future Langfuse/OpenTelemetry adapters.

Modify:

- `src/main/java/io/github/imzmq/interview/observability/application/TraceAttributeSanitizer.java`
  Extend allowlist for model latency/cost/retrieval metadata that is safe to export.
- `src/main/java/io/github/imzmq/interview/knowledge/application/observability/DefaultTraceService.java`
  Publish sanitized RAG node success/failure events through `AiObservationPublisher`.
- `src/test/java/io/github/imzmq/interview/service/RAGObservabilityServiceTest.java` or create a focused `DefaultTraceServiceTest` if easier.
  Verify RAG trace service publishes sanitized events.
- `docs/architecture/framework-adoption-roadmap.md`
  Mark Phase 1 foundation as the first implementation slice and link the guidelines.
- `AGENTS.md`
  Link the observability guide under documentation maintenance.

---

### Task 1: Add AI observation event contract

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/observability/application/AiObservationEvent.java`
- Create: `src/test/java/io/github/imzmq/interview/observability/application/AiObservationEventTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/imzmq/interview/observability/application/AiObservationEventTest.java`:

```java
package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiObservationEventTest {

    @Test
    void createsImmutableRagNodeEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", "glm-4");
        attrs.put("docCount", 3);

        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "knowledge retrieval",
                "success",
                attrs
        );

        assertThat(event.eventType()).isEqualTo("rag.node");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.nodeId()).isEqualTo("node-1");
        assertThat(event.category()).isEqualTo("retrieval");
        assertThat(event.name()).isEqualTo("knowledge retrieval");
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.attributes()).containsEntry("model", "glm-4");
        assertThat(event.attributes()).containsEntry("docCount", 3);

        attrs.put("model", "changed");
        assertThat(event.attributes()).containsEntry("model", "glm-4");
        assertThatThrownBy(() -> event.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesBlankValues() {
        AiObservationEvent event = AiObservationEvent.ragNode(
                " trace-1 ",
                " node-1 ",
                " retrieval ",
                " knowledge retrieval ",
                " success ",
                null
        );

        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.nodeId()).isEqualTo("node-1");
        assertThat(event.category()).isEqualTo("retrieval");
        assertThat(event.name()).isEqualTo("knowledge retrieval");
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.attributes()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=AiObservationEventTest test
```

Expected: FAIL because `AiObservationEvent` does not exist.

- [ ] **Step 3: Implement the event record**

Create `src/main/java/io/github/imzmq/interview/observability/application/AiObservationEvent.java`:

```java
package io.github.imzmq.interview.observability.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record AiObservationEvent(
        String eventType,
        String traceId,
        String nodeId,
        String category,
        String name,
        String status,
        Instant eventTime,
        Map<String, Object> attributes
) {

    public AiObservationEvent {
        eventType = normalize(eventType);
        traceId = normalize(traceId);
        nodeId = normalize(nodeId);
        category = normalize(category);
        name = normalize(name);
        status = normalize(status);
        eventTime = eventTime == null ? Instant.now() : eventTime;
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public static AiObservationEvent ragNode(String traceId,
                                             String nodeId,
                                             String category,
                                             String name,
                                             String status,
                                             Map<String, Object> attributes) {
        return new AiObservationEvent(
                "rag.node",
                traceId,
                nodeId,
                category,
                name,
                status,
                Instant.now(),
                attributes
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```bash
mvn -q -Dtest=AiObservationEventTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/observability/application/AiObservationEvent.java \
        src/test/java/io/github/imzmq/interview/observability/application/AiObservationEventTest.java
git commit -m "feat(observability): add AI observation event contract"
```

---

### Task 2: Add safe publisher port and default implementation

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/observability/application/AiObservationPublisher.java`
- Create: `src/main/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisher.java`
- Create: `src/test/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisherTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisherTest.java`:

```java
package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoopAiObservationPublisherTest {

    @Test
    void publishNeverThrowsForNullOrNormalEvent() {
        NoopAiObservationPublisher publisher = new NoopAiObservationPublisher();

        assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "knowledge retrieval",
                "success",
                Map.of("model", "glm-4")
        ))).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=NoopAiObservationPublisherTest test
```

Expected: FAIL because `NoopAiObservationPublisher` does not exist.

- [ ] **Step 3: Implement publisher port and no-op adapter**

Create `src/main/java/io/github/imzmq/interview/observability/application/AiObservationPublisher.java`:

```java
package io.github.imzmq.interview.observability.application;

public interface AiObservationPublisher {

    void publish(AiObservationEvent event);
}
```

Create `src/main/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisher.java`:

```java
package io.github.imzmq.interview.observability.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(AiObservationPublisher.class)
public class NoopAiObservationPublisher implements AiObservationPublisher {

    private static final Logger logger = LoggerFactory.getLogger(NoopAiObservationPublisher.class);

    @Override
    public void publish(AiObservationEvent event) {
        if (event == null) {
            return;
        }
        logger.debug("AI observation event ignored by noop publisher: type={}, traceId={}, nodeId={}, status={}",
                event.eventType(), event.traceId(), event.nodeId(), event.status());
    }
}
```

- [ ] **Step 4: Run focused tests and verify they pass**

Run:

```bash
mvn -q -Dtest=AiObservationEventTest,NoopAiObservationPublisherTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/observability/application/AiObservationPublisher.java \
        src/main/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisher.java \
        src/test/java/io/github/imzmq/interview/observability/application/NoopAiObservationPublisherTest.java
git commit -m "feat(observability): add AI observation publisher port"
```

---

### Task 3: Extend safe trace attribute allowlist

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/observability/application/TraceAttributeSanitizer.java`
- Test: `src/test/java/io/github/imzmq/interview/config/ObservabilitySwitchPropertiesTest.java` is unrelated; create focused test below.
- Create: `src/test/java/io/github/imzmq/interview/observability/application/TraceAttributeSanitizerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/imzmq/interview/observability/application/TraceAttributeSanitizerTest.java`:

```java
package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAttributeSanitizerTest {

    @Test
    void keepsOnlySafeAiObservationKeys() {
        TraceAttributeSanitizer sanitizer = new TraceAttributeSanitizer();

        Map<String, Object> sanitized = sanitizer.sanitize(Map.of(
                "provider", "zhipu",
                "model", "glm-4",
                "latencyMs", 123,
                "promptTokens", 10,
                "completionTokens", 20,
                "totalTokens", 30,
                "estimatedCost", "0.001",
                "retrievalMode", "hybrid",
                "docCount", 4,
                "secret", "must-not-leak"
        ));

        assertThat(sanitized).containsEntry("provider", "zhipu");
        assertThat(sanitized).containsEntry("model", "glm-4");
        assertThat(sanitized).containsEntry("latencyMs", "123");
        assertThat(sanitized).containsEntry("promptTokens", "10");
        assertThat(sanitized).containsEntry("completionTokens", "20");
        assertThat(sanitized).containsEntry("totalTokens", "30");
        assertThat(sanitized).containsEntry("estimatedCost", "0.001");
        assertThat(sanitized).containsEntry("retrievalMode", "hybrid");
        assertThat(sanitized).containsEntry("docCount", "4");
        assertThat(sanitized).doesNotContainKey("secret");
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=TraceAttributeSanitizerTest test
```

Expected: FAIL because the new token/cost/latency keys are filtered out.

- [ ] **Step 3: Extend allowlist**

Modify `TraceAttributeSanitizer.ALLOWED_KEYS` to include:

```java
"latencyMs",
"promptTokens",
"completionTokens",
"totalTokens",
"estimatedCost",
"routeType",
"candidateId",
"candidatePriority",
"circuitState",
"nodeType",
"nodeName"
```

Keep `MAX_VALUE_LENGTH = 120` unchanged.

- [ ] **Step 4: Run focused tests and verify they pass**

Run:

```bash
mvn -q -Dtest=TraceAttributeSanitizerTest,AiObservationEventTest,NoopAiObservationPublisherTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/observability/application/TraceAttributeSanitizer.java \
        src/test/java/io/github/imzmq/interview/observability/application/TraceAttributeSanitizerTest.java
git commit -m "feat(observability): allow safe AI trace attributes"
```

---

### Task 4: Publish RAG trace node events through the port

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/observability/DefaultTraceService.java`
- Create: `src/test/java/io/github/imzmq/interview/observability/application/RecordingAiObservationPublisher.java`
- Create: `src/test/java/io/github/imzmq/interview/knowledge/application/observability/DefaultTraceServiceTest.java`

- [ ] **Step 1: Create test helper publisher**

Create `src/test/java/io/github/imzmq/interview/observability/application/RecordingAiObservationPublisher.java`:

```java
package io.github.imzmq.interview.observability.application;

import java.util.ArrayList;
import java.util.List;

public class RecordingAiObservationPublisher implements AiObservationPublisher {

    private final List<AiObservationEvent> events = new ArrayList<>();

    @Override
    public void publish(AiObservationEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    public List<AiObservationEvent> events() {
        return List.copyOf(events);
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/io/github/imzmq/interview/knowledge/application/observability/DefaultTraceServiceTest.java`:

```java
package io.github.imzmq.interview.knowledge.application.observability;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.RecordingAiObservationPublisher;
import io.github.imzmq.interview.observability.application.TraceAttributeSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTraceServiceTest {

    @Test
    void publishesSanitizedSuccessEvent() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService ragObservabilityService = new RAGObservabilityService(properties);
        RecordingAiObservationPublisher publisher = new RecordingAiObservationPublisher();
        DefaultTraceService traceService = new DefaultTraceService(
                ragObservabilityService,
                new TraceAttributeSanitizer(),
                publisher
        );

        TraceNodeHandle handle = traceService.startRoot(
                "trace-1",
                TraceNodeDefinitions.RAG_PIPELINE,
                Map.of("secret", "must-not-leak")
        );
        traceService.success(handle, Map.of(
                "model", "glm-4",
                "docCount", 3,
                "secret", "must-not-leak"
        ));

        assertThat(publisher.events()).hasSize(1);
        AiObservationEvent event = publisher.events().get(0);
        assertThat(event.eventType()).isEqualTo("rag.node");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.nodeId()).isEqualTo(handle.nodeId());
        assertThat(event.category()).isEqualTo(TraceNodeDefinitions.RAG_PIPELINE.nodeType());
        assertThat(event.name()).isEqualTo(TraceNodeDefinitions.RAG_PIPELINE.nodeName());
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.attributes()).containsEntry("model", "glm-4");
        assertThat(event.attributes()).containsEntry("docCount", "3");
        assertThat(event.attributes()).doesNotContainKey("secret");
    }

    @Test
    void publishesFailureEventWithoutThrowing() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagTraceEnabled(true);
        RAGObservabilityService ragObservabilityService = new RAGObservabilityService(properties);
        RecordingAiObservationPublisher publisher = new RecordingAiObservationPublisher();
        DefaultTraceService traceService = new DefaultTraceService(
                ragObservabilityService,
                new TraceAttributeSanitizer(),
                publisher
        );

        TraceNodeHandle handle = traceService.startRoot(
                "trace-2",
                TraceNodeDefinitions.RAG_PIPELINE,
                Map.of()
        );
        traceService.fail(handle, "model timeout", Map.of("fallbackReason", "timeout"));

        assertThat(publisher.events()).hasSize(1);
        AiObservationEvent event = publisher.events().get(0);
        assertThat(event.status()).isEqualTo("failed");
        assertThat(event.attributes()).containsEntry("fallbackReason", "timeout");
        assertThat(event.attributes()).containsEntry("error", "model timeout");
    }
}
```

- [ ] **Step 3: Run the focused test and verify it fails**

Run:

```bash
mvn -q -Dtest=DefaultTraceServiceTest test
```

Expected: FAIL because `DefaultTraceService` has no constructor accepting `AiObservationPublisher` and does not publish events.

- [ ] **Step 4: Inject publisher and publish sanitized events**

Modify `DefaultTraceService`:

```java
private final AiObservationPublisher aiObservationPublisher;
```

Update imports:

```java
import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
```

Replace constructor with an overloaded pair:

```java
public DefaultTraceService(RAGObservabilityService ragObservabilityService,
                           TraceAttributeSanitizer traceAttributeSanitizer,
                           AiObservationPublisher aiObservationPublisher) {
    this.ragObservabilityService = ragObservabilityService;
    this.traceAttributeSanitizer = traceAttributeSanitizer;
    this.aiObservationPublisher = aiObservationPublisher == null ? new NoopAiObservationPublisher() : aiObservationPublisher;
}

public DefaultTraceService(RAGObservabilityService ragObservabilityService,
                           TraceAttributeSanitizer traceAttributeSanitizer) {
    this(ragObservabilityService, traceAttributeSanitizer, new NoopAiObservationPublisher());
}
```

At the end of `success(...)`, after `ragObservabilityService.endNode(...)`, add:

```java
publishObservation(handle, "success", result, null);
```

At the end of `fail(...)`, after `ragObservabilityService.endNode(...)`, add:

```java
publishObservation(handle, "failed", result, errorMessage);
```

Add helper method:

```java
private void publishObservation(TraceNodeHandle handle,
                                String status,
                                Map<String, Object> payload,
                                String errorMessage) {
    Map<String, Object> sanitized = new java.util.LinkedHashMap<>(traceAttributeSanitizer.sanitize(payload));
    if (errorMessage != null && !errorMessage.isBlank()) {
        sanitized.put("error", errorMessage.length() > 120 ? errorMessage.substring(0, 120) : errorMessage);
    }
    aiObservationPublisher.publish(AiObservationEvent.ragNode(
            handle.traceId(),
            handle.nodeId(),
            handle.definition().nodeType(),
            handle.definition().nodeName(),
            status,
            sanitized
    ));
}
```

- [ ] **Step 5: Run focused tests and verify they pass**

Run:

```bash
mvn -q -Dtest=DefaultTraceServiceTest,TraceAttributeSanitizerTest,AiObservationEventTest,NoopAiObservationPublisherTest test
```

Expected: PASS.

- [ ] **Step 6: Run compile check**

Run:

```bash
mvn -q compile
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/observability/DefaultTraceService.java \
        src/test/java/io/github/imzmq/interview/observability/application/RecordingAiObservationPublisher.java \
        src/test/java/io/github/imzmq/interview/knowledge/application/observability/DefaultTraceServiceTest.java
git commit -m "feat(observability): publish RAG trace observation events"
```

---

### Task 5: Document Phase 1 observability adapter rules

**Files:**
- Create: `docs/development/observability-guidelines.md`
- Modify: `docs/architecture/framework-adoption-roadmap.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Create observability developer guide**

Create `docs/development/observability-guidelines.md`:

```markdown
# 可观测性开发规范

本文档说明 Study-Agent 当前 AI/RAG/Agent 可观测性边界，以及后续接入 Langfuse/OpenTelemetry 时的规则。

## 当前边界

当前项目保留自研 RAG trace：

- `knowledge.application.observability.RAGObservabilityService`：本地 RAG trace 记录、查询和 SSE 发布。
- `observability.application.TraceAttributeSanitizer`：过滤可导出的 trace 属性，避免泄漏 prompt、密钥和用户敏感内容。
- `observability.application.AiObservationPublisher`：框架中立的 AI 观测事件发布端口。
- `observability.application.NoopAiObservationPublisher`：默认实现，不连接任何外部系统。

业务代码只能依赖 `AiObservationPublisher` 端口，不能直接依赖 Langfuse、OpenTelemetry 或其他外部 SDK。

## 事件规则

事件必须满足：

- 不包含 raw prompt、raw completion、Authorization header、cookie、API key。
- 只使用 `TraceAttributeSanitizer` allowlist 中的字段。
- 发布失败不能影响主业务流程。
- RAG、模型路由、Agent action 应发布结构化事件，而不是拼接日志文本。

推荐属性：

```text
provider, model, latencyMs, promptTokens, completionTokens, totalTokens,
retrievalMode, docCount, imageCount, fallbackReason, routeType, circuitState
```

## 后续接入 Langfuse

接入 Langfuse 时新增 adapter，例如：

```text
observability.infrastructure.langfuse.LangfuseAiObservationPublisher
```

要求：

- 通过配置开关启用。
- 默认本地开发仍使用 no-op publisher。
- adapter 内部处理 Langfuse SDK、认证、重试和失败降级。
- 不修改 RAG/Agent/ModelRouting 的业务流程。

## 后续接入 OpenTelemetry

OpenTelemetry 用于系统级 trace/metrics/logs：

- HTTP request span。
- 数据库/Redis span。
- 模型调用外层 span。
- RAG pipeline span。

OpenTelemetry 不替代 Langfuse 的 LLM/RAG 细节追踪，两者通过 traceId 关联。
```

- [ ] **Step 2: Update roadmap Phase 1 status**

In `docs/architecture/framework-adoption-roadmap.md`, under `### Phase 1：可观测性与评测闭环`, add after the goal list:

```markdown
第一步实施切片：先落地 `AiObservationPublisher` 端口和 `docs/development/observability-guidelines.md`，保证后续 Langfuse/OpenTelemetry 只作为 adapter 接入。
```

- [ ] **Step 3: Update AGENTS documentation maintenance section**

In `AGENTS.md`, under documentation maintenance bullets, add:

```markdown
- AI/RAG/Agent 可观测性事件、trace 字段或外部观测 adapter 变化，见 `docs/development/observability-guidelines.md`。
```

- [ ] **Step 4: Run documentation checks**

Run:

```bash
git diff --check
test -f docs/development/observability-guidelines.md
test -f docs/architecture/framework-adoption-roadmap.md
```

Expected: all commands exit 0.

- [ ] **Step 5: Commit**

```bash
git add -f docs/development/observability-guidelines.md \
        docs/architecture/framework-adoption-roadmap.md \
        AGENTS.md
git commit -m "docs: document AI observability adapter rules"
```

---

### Task 6: Final verification for Phase 1 foundation

**Files:**
- No new files unless fixes are needed.

- [ ] **Step 1: Run focused observability tests**

Run:

```bash
mvn -q -Dtest=AiObservationEventTest,NoopAiObservationPublisherTest,TraceAttributeSanitizerTest,DefaultTraceServiceTest test
```

Expected: PASS.

- [ ] **Step 2: Run compile and test compile**

Run:

```bash
mvn -q compile
mvn -q -DskipTests test-compile
```

Expected: PASS.

- [ ] **Step 3: Run architecture test**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS. If it fails because the new observability port package violates a rule, update `ArchitectureRulesTest` only if the rule is too narrow and the new dependency direction is still consistent with `ARCHITECTURE.md`.

- [ ] **Step 4: Run verify without tests**

Run:

```bash
mvn -q verify -DskipTests
```

Expected: PASS.

- [ ] **Step 5: Run git whitespace check**

Run:

```bash
git diff --check HEAD~5..HEAD
```

Expected: PASS.

- [ ] **Step 6: Prepare summary**

Summarize:

- New `AiObservationEvent` contract.
- New `AiObservationPublisher` port and no-op adapter.
- RAG trace publication through the port.
- Updated observability guidelines and roadmap.
- Exact commands run and results.

Do not claim Langfuse/OpenTelemetry are integrated; this phase only creates the adapter boundary.

---

## Self-Review

- Spec coverage: Covers Phase 1 foundation from `docs/architecture/framework-adoption-roadmap.md`; leaves actual Langfuse/OpenTelemetry SDK integration for a later plan.
- Placeholder scan: No incomplete placeholders are present.
- Type consistency: `AiObservationEvent`, `AiObservationPublisher`, `NoopAiObservationPublisher`, and `RecordingAiObservationPublisher` names are consistent across tasks.
- Risk control: Default publisher is no-op and publication failures do not change RAG/model-routing behavior.
