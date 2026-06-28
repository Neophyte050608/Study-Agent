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
    void execute_returns_failed_result_when_request_is_null() {
        SkillBackedToolExecutionAdapter adapter = adapterWith();

        ToolExecutionResult result = adapter.execute(null);

        assertThat(result.toolId()).isEmpty();
        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.message()).isEqualTo("tool_id_required");
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
                    SkillExecutionMode.WORKFLOW,
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
