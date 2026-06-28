# Tool Execution Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a lightweight `ToolExecutionPort` boundary that lets future Agent Runtime code invoke tools without depending on concrete Skill, MCP, or framework implementations.

**Architecture:** Add a new `tool` module with core request/result/definition models and a runtime adapter backed by the existing `SkillOrchestrator`. The adapter maps Tool requests into `SkillExecutionContext`, reuses current retry/timeout/fallback behavior, and maps Skill results back into stable Tool results.

**Tech Stack:** Java 21 records/enums, Spring Boot service wiring, JUnit 5, AssertJ, existing `skill` runtime.

---

## File Structure

- Create `src/main/java/io/github/imzmq/interview/tool/core/ToolDefinition.java` — immutable tool metadata exposed to agent/application code.
- Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionPort.java` — stable execution interface.
- Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionRequest.java` — immutable tool invocation request with safe defaults.
- Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionResult.java` — immutable tool invocation result plus factory helpers.
- Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionStatus.java` — status enum aligned with existing skill statuses.
- Create `src/main/java/io/github/imzmq/interview/tool/policy/ToolRiskLevel.java` — lightweight risk classification for future permission policy.
- Create `src/main/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapter.java` — Spring `@Service` adapter from Tool Port to `SkillOrchestrator`.
- Create `src/test/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapterTest.java` — unit tests with in-memory fake skills.
- Modify `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java` — guard against controllers depending on tool runtime implementations.
- Do not modify `AGENTS.md` unless removing unrelated local noise before commit.

---

### Task 1: Add Tool Core Models

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionStatus.java`
- Create: `src/main/java/io/github/imzmq/interview/tool/policy/ToolRiskLevel.java`
- Create: `src/main/java/io/github/imzmq/interview/tool/core/ToolDefinition.java`
- Create: `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionRequest.java`
- Create: `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionResult.java`
- Create: `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionPort.java`

- [ ] **Step 1: Create status and risk enums**

Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionStatus.java`:

```java
package io.github.imzmq.interview.tool.core;

public enum ToolExecutionStatus {
    SUCCESS,
    FALLBACK,
    FAILED,
    SKIPPED
}
```

Create `src/main/java/io/github/imzmq/interview/tool/policy/ToolRiskLevel.java`:

```java
package io.github.imzmq.interview.tool.policy;

public enum ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
```

- [ ] **Step 2: Create `ToolDefinition`**

Create `src/main/java/io/github/imzmq/interview/tool/core/ToolDefinition.java`:

```java
package io.github.imzmq.interview.tool.core;

import java.util.List;

public record ToolDefinition(
        String id,
        String name,
        String description,
        List<String> capabilities
) {

    public ToolDefinition {
        id = safeText(id);
        name = safeText(name).isBlank() ? id : safeText(name);
        description = safeText(description);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 3: Create `ToolExecutionRequest`**

Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionRequest.java`:

```java
package io.github.imzmq.interview.tool.core;

import io.github.imzmq.interview.tool.policy.ToolRiskLevel;

import java.util.Map;

public record ToolExecutionRequest(
        String traceId,
        String operator,
        String toolId,
        Map<String, Object> input,
        String source,
        ToolRiskLevel riskLevel,
        boolean dryRun,
        Map<String, Object> metadata
) {

    public ToolExecutionRequest {
        traceId = safeText(traceId);
        operator = safeText(operator).isBlank() ? "anonymous" : safeText(operator);
        toolId = safeText(toolId);
        input = input == null ? Map.of() : Map.copyOf(input);
        source = safeText(source).isBlank() ? "agent" : safeText(source);
        riskLevel = riskLevel == null ? ToolRiskLevel.LOW : riskLevel;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolExecutionRequest of(String toolId, String operator, Map<String, Object> input) {
        return new ToolExecutionRequest("", operator, toolId, input, "agent", ToolRiskLevel.LOW, false, Map.of());
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 4: Create `ToolExecutionResult`**

Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionResult.java`:

```java
package io.github.imzmq.interview.tool.core;

import java.util.Map;

public record ToolExecutionResult(
        String toolId,
        ToolExecutionStatus status,
        Map<String, Object> output,
        String message,
        int attempts,
        boolean fallbackUsed,
        long latencyMs
) {

    public ToolExecutionResult {
        toolId = safeText(toolId);
        status = status == null ? ToolExecutionStatus.FAILED : status;
        output = output == null ? Map.of() : Map.copyOf(output);
        message = safeText(message);
        attempts = Math.max(0, attempts);
        latencyMs = Math.max(0L, latencyMs);
    }

    public static ToolExecutionResult success(String toolId, Map<String, Object> output, int attempts, long latencyMs) {
        return new ToolExecutionResult(toolId, ToolExecutionStatus.SUCCESS, output, "ok", attempts, false, latencyMs);
    }

    public static ToolExecutionResult failed(String toolId, String message) {
        return new ToolExecutionResult(toolId, ToolExecutionStatus.FAILED, Map.of(), message, 0, false, 0L);
    }

    public static ToolExecutionResult skipped(String toolId, String message) {
        return new ToolExecutionResult(toolId, ToolExecutionStatus.SKIPPED, Map.of(), message, 0, false, 0L);
    }

    public boolean succeeded() {
        return status == ToolExecutionStatus.SUCCESS;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **Step 5: Create `ToolExecutionPort`**

Create `src/main/java/io/github/imzmq/interview/tool/core/ToolExecutionPort.java`:

```java
package io.github.imzmq.interview.tool.core;

public interface ToolExecutionPort {

    ToolExecutionResult execute(ToolExecutionRequest request);

    ToolDefinition definition(String toolId);
}
```

- [ ] **Step 6: Compile the new core types**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: build compiles with no errors related to the new `tool` package.

- [ ] **Step 7: Commit core models**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/tool/core src/main/java/io/github/imzmq/interview/tool/policy
git commit -m "feat: add tool execution core models"
```

---

### Task 2: Add Skill-Backed Tool Adapter with Tests

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapter.java`
- Create: `src/test/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapterTest.java`

- [ ] **Step 1: Write failing adapter tests**

Create `src/test/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapterTest.java`:

```java
package io.github.imzmq.interview.tool.runtime;

import io.github.imzmq.interview.config.skill.SkillExecutionProperties;
import io.github.imzmq.interview.skill.core.ExecutableSkill;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionMode;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.policy.SkillFailureFallbackMode;
import io.github.imzmq.interview.skill.policy.SkillFailurePolicy;
import io.github.imzmq.interview.skill.runtime.SkillExecutor;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import io.github.imzmq.interview.skill.runtime.SkillRegistry;
import io.github.imzmq.interview.tool.core.ToolDefinition;
import io.github.imzmq.interview.tool.core.ToolExecutionRequest;
import io.github.imzmq.interview.tool.core.ToolExecutionResult;
import io.github.imzmq.interview.tool.core.ToolExecutionStatus;
import io.github.imzmq.interview.tool.policy.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SkillBackedToolExecutionAdapterTest {

    @Test
    void execute_maps_successful_skill_result_to_tool_result() {
        CapturingSkill skill = new CapturingSkill("query-optimizer",
                SkillExecutionResult.success("query-optimizer", Map.of("query", "java 并发"), 2, List.of("search")));
        SkillBackedToolExecutionAdapter adapter = adapterWith(skill);

        ToolExecutionResult result = adapter.execute(new ToolExecutionRequest(
                "trace-1",
                "alice",
                "query-optimizer",
                Map.of("question", "讲讲并发"),
                "agent-test",
                ToolRiskLevel.MEDIUM,
                true,
                Map.of("requestId", "req-1")
        ));

        assertThat(result.toolId()).isEqualTo("query-optimizer");
        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(result.output()).containsEntry("query", "java 并发");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);

        SkillExecutionContext captured = skill.capturedContext.get();
        assertThat(captured.traceId()).isEqualTo("trace-1");
        assertThat(captured.operator()).isEqualTo("alice");
        assertThat(captured.text("question")).isEqualTo("讲讲并发");
        assertThat(captured.text("source")).isEqualTo("agent-test");
        assertThat(captured.text("riskLevel")).isEqualTo("MEDIUM");
        assertThat(captured.bool("dryRun")).isTrue();
        assertThat(captured.text("requestId")).isEqualTo("req-1");
    }

    @Test
    void execute_returns_failed_result_when_tool_id_is_blank() {
        SkillBackedToolExecutionAdapter adapter = adapterWith();

        ToolExecutionResult result = adapter.execute(ToolExecutionRequest.of(" ", "alice", Map.of()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.message()).isEqualTo("tool_id_required");
    }

    @Test
    void execute_returns_skipped_result_when_skill_is_not_registered() {
        SkillBackedToolExecutionAdapter adapter = adapterWith();

        ToolExecutionResult result = adapter.execute(ToolExecutionRequest.of("missing-tool", "alice", Map.of()));

        assertThat(result.toolId()).isEqualTo("missing-tool");
        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SKIPPED);
        assertThat(result.message()).isEqualTo("tool_not_registered");
    }

    @Test
    void definition_maps_skill_definition_to_tool_definition() {
        SkillBackedToolExecutionAdapter adapter = adapterWith(new CapturingSkill("report-generator",
                SkillExecutionResult.success("report-generator", Map.of(), 1, List.of())));

        ToolDefinition definition = adapter.definition("report-generator");

        assertThat(definition).isNotNull();
        assertThat(definition.id()).isEqualTo("report-generator");
        assertThat(definition.name()).isEqualTo("report-generator");
        assertThat(definition.description()).isEqualTo("Test skill report-generator");
        assertThat(definition.capabilities()).containsExactly("mcp.search", "mcp.write");
    }

    @Test
    void execute_maps_fallback_status_and_message() {
        SkillBackedToolExecutionAdapter adapter = adapterWith(new CapturingSkill("unstable-tool",
                SkillExecutionResult.fallback("unstable-tool", "cached_result_unavailable:timeout", 3)));

        ToolExecutionResult result = adapter.execute(ToolExecutionRequest.of("unstable-tool", "alice", Map.of()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FALLBACK);
        assertThat(result.message()).isEqualTo("cached_result_unavailable:timeout");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.fallbackUsed()).isTrue();
    }

    private static SkillBackedToolExecutionAdapter adapterWith(ExecutableSkill... skills) {
        SkillRegistry registry = new SkillRegistry(skills == null ? List.of() : List.of(skills));
        SkillExecutionProperties properties = new SkillExecutionProperties();
        SkillExecutor executor = new SkillExecutor(properties);
        SkillOrchestrator orchestrator = new SkillOrchestrator(registry, executor, properties);
        return new SkillBackedToolExecutionAdapter(orchestrator);
    }

    private static final class CapturingSkill implements ExecutableSkill {
        private final SkillDefinition definition;
        private final SkillExecutionResult result;
        private final AtomicReference<SkillExecutionContext> capturedContext = new AtomicReference<>();

        private CapturingSkill(String id, SkillExecutionResult result) {
            this.definition = new SkillDefinition(
                    id,
                    "Test skill " + id,
                    SkillExecutionMode.SYNC,
                    List.of("mcp.search", "mcp.write"),
                    new SkillFailurePolicy(1, 1000L, 0L, 3, 1000L, SkillFailureFallbackMode.SKIP_SKILL)
            );
            this.result = result;
        }

        @Override
        public SkillDefinition definition() {
            return definition;
        }

        @Override
        public SkillExecutionResult execute(SkillExecutionContext context) {
            capturedContext.set(context);
            return result;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail because adapter is missing**

Run:

```bash
mvn -q -Dtest=SkillBackedToolExecutionAdapterTest test
```

Expected: compilation fails with `cannot find symbol` for `SkillBackedToolExecutionAdapter`.

- [ ] **Step 3: Implement `SkillBackedToolExecutionAdapter`**

Create `src/main/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapter.java`:

```java
package io.github.imzmq.interview.tool.runtime;

import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.core.SkillExecutionStatus;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import io.github.imzmq.interview.tool.core.ToolDefinition;
import io.github.imzmq.interview.tool.core.ToolExecutionPort;
import io.github.imzmq.interview.tool.core.ToolExecutionRequest;
import io.github.imzmq.interview.tool.core.ToolExecutionResult;
import io.github.imzmq.interview.tool.core.ToolExecutionStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SkillBackedToolExecutionAdapter implements ToolExecutionPort {

    private final SkillOrchestrator skillOrchestrator;

    public SkillBackedToolExecutionAdapter(SkillOrchestrator skillOrchestrator) {
        this.skillOrchestrator = skillOrchestrator;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        ToolExecutionRequest normalized = request == null
                ? new ToolExecutionRequest("", "anonymous", "", Map.of(), "agent", null, false, Map.of())
                : request;
        if (normalized.toolId().isBlank()) {
            return ToolExecutionResult.failed(normalized.toolId(), "tool_id_required");
        }
        if (skillOrchestrator.definition(normalized.toolId()) == null) {
            return ToolExecutionResult.skipped(normalized.toolId(), "tool_not_registered");
        }

        long start = System.currentTimeMillis();
        SkillExecutionResult skillResult = skillOrchestrator.execute(
                normalized.toolId(),
                new SkillExecutionContext(
                        normalized.traceId(),
                        normalized.operator(),
                        toSkillInput(normalized),
                        skillOrchestrator.newBudget()
                )
        );
        long latencyMs = System.currentTimeMillis() - start;
        return toToolResult(normalized.toolId(), skillResult, latencyMs);
    }

    @Override
    public ToolDefinition definition(String toolId) {
        SkillDefinition definition = skillOrchestrator.definition(toolId);
        if (definition == null) {
            return null;
        }
        return new ToolDefinition(
                definition.id(),
                definition.id(),
                definition.description(),
                definition.allowedMcpCapabilities()
        );
    }

    private Map<String, Object> toSkillInput(ToolExecutionRequest request) {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.putAll(request.input());
        input.put("source", request.source());
        input.put("riskLevel", request.riskLevel().name());
        input.put("dryRun", request.dryRun());
        input.putAll(request.metadata());
        return Map.copyOf(input);
    }

    private ToolExecutionResult toToolResult(String toolId, SkillExecutionResult skillResult, long latencyMs) {
        if (skillResult == null) {
            return new ToolExecutionResult(toolId, ToolExecutionStatus.FAILED, Map.of(), "skill_result_missing", 0, false, latencyMs);
        }
        return new ToolExecutionResult(
                toolId,
                toToolStatus(skillResult.status()),
                skillResult.output(),
                skillResult.message(),
                skillResult.attempts(),
                skillResult.fallbackUsed(),
                latencyMs
        );
    }

    private ToolExecutionStatus toToolStatus(SkillExecutionStatus status) {
        if (status == SkillExecutionStatus.SUCCESS) {
            return ToolExecutionStatus.SUCCESS;
        }
        if (status == SkillExecutionStatus.FALLBACK) {
            return ToolExecutionStatus.FALLBACK;
        }
        if (status == SkillExecutionStatus.SKIPPED) {
            return ToolExecutionStatus.SKIPPED;
        }
        return ToolExecutionStatus.FAILED;
    }
}
```

- [ ] **Step 4: Run adapter tests**

Run:

```bash
mvn -q -Dtest=SkillBackedToolExecutionAdapterTest test
```

Expected: all tests in `SkillBackedToolExecutionAdapterTest` pass.

- [ ] **Step 5: Commit adapter and tests**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapter.java src/test/java/io/github/imzmq/interview/tool/runtime/SkillBackedToolExecutionAdapterTest.java
git commit -m "feat: adapt skills to tool execution port"
```

---

### Task 3: Add Architecture Guardrail

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Add ArchUnit rule for controller/API dependency direction**

Modify `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java` by adding this rule after `agent_should_not_depend_on_controller`:

```java
    @ArchTest
    static final ArchRule controller_should_not_depend_on_tool_runtime_implementations =
            noClasses().that().resideInAnyPackage("..controller..", "..api..")
                    .should().dependOnClassesThat().resideInAnyPackage("..tool.runtime..");
```

- [ ] **Step 2: Run architecture test**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: `ArchitectureRulesTest` passes. If it fails, inspect the violation and remove any API/controller dependency on `..tool.runtime..`; APIs should depend on `ToolExecutionPort` or application services instead.

- [ ] **Step 3: Commit architecture guardrail**

Run:

```bash
git add src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
git commit -m "test: guard tool runtime boundaries"
```

---

### Task 4: Verification and Cleanup

**Files:**
- Review: `AGENTS.md`
- Review: all files changed in Tasks 1-3

- [ ] **Step 1: Remove unrelated local AGENTS.md noise if present**

Run:

```bash
git diff -- AGENTS.md
```

If the diff only contains a `<claude-mem-context>` block, remove that block and keep the repository guide unchanged. Use an editor or this command only when the diff exactly matches the memory-context noise:

```bash
python3 - <<'PY'
from pathlib import Path
path = Path('AGENTS.md')
text = path.read_text()
start = text.find('\n\n<claude-mem-context>')
if start != -1:
    path.write_text(text[:start].rstrip() + '\n')
PY
```

- [ ] **Step 2: Check whitespace and final diff**

Run:

```bash
git diff --check
git status --short
git diff --stat
```

Expected: `git diff --check` produces no output. Status only shows intended changes if cleanup created any uncommitted file.

- [ ] **Step 3: Run focused tests**

Run:

```bash
mvn -q -Dtest=SkillBackedToolExecutionAdapterTest,ArchitectureRulesTest test
```

Expected: focused tests pass.

- [ ] **Step 4: Run full test suite**

Run:

```bash
mvn -q test
```

Expected: full suite passes. If it fails with the known Mockito inline / ByteBuddy self-attach environment issue, capture the failing error text and do not claim full-suite success.

- [ ] **Step 5: Commit cleanup if needed**

If Step 1 modified `AGENTS.md`, run:

```bash
git add AGENTS.md
git commit -m "chore: remove local agent memory noise"
```

If there were no cleanup changes, do not create an empty commit.

- [ ] **Step 6: Final review summary**

Run:

```bash
git log --oneline --decorate -6
git status -sb
```

Expected: working tree is clean and branch is ahead of `origin/main` by the new implementation commits.
