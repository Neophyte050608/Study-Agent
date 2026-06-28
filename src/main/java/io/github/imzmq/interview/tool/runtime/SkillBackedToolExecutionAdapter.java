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
        input.putAll(request.metadata());
        input.put("source", request.source());
        input.put("riskLevel", request.riskLevel().name());
        input.put("dryRun", request.dryRun());
        return Map.copyOf(input);
    }

    private ToolExecutionResult toToolResult(String toolId, SkillExecutionResult skillResult, long latencyMs) {
        if (skillResult == null) {
            return new ToolExecutionResult(
                    toolId,
                    ToolExecutionStatus.FAILED,
                    Map.of(),
                    "skill_result_missing",
                    0,
                    false,
                    latencyMs
            );
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
