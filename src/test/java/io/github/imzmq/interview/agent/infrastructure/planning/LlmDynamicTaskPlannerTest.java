package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentDefinition;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmDynamicTaskPlannerTest {
    private static final String VALID_PLAN = """
            {"schemaVersion":"1","nodes":[
              {"id":"evidence","capability":"knowledge.evidence.retrieve","dependsOn":[],"arguments":{"limit":4}},
              {"id":"compose","capability":"learning.plan.compose","dependsOn":["evidence"],"arguments":{}}
            ]}
            """;

    @Test
    void sendsOnlyTheBoundedPlanningViewAndParsesExactJson() {
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userPrompt = new AtomicReference<>();
        DynamicPlanningModelOperations model = (system, user) -> {
            systemPrompt.set(system);
            userPrompt.set(user);
            return VALID_PLAN;
        };

        DynamicPlanParseResult result = planner(model).plan(
                new DynamicExecutionContext("private-owner", "Java", "参考资料"), descriptors());

        assertThat(result.success()).isTrue();
        assertThat(result.plan().nodes()).hasSize(2);
        assertThat(systemPrompt.get()).contains(
                "Return one JSON object only", "schemaVersion \"1\"", "Do not use markdown");
        assertThat(userPrompt.get()).contains(
                "Java", "参考资料", "knowledge.evidence.retrieve", "learning.plan.compose", "limit");
        assertThat(userPrompt.get()).doesNotContain(
                "private-owner", "Bean", "API_KEY", "profile content", "evidence content");
    }

    @Test
    void callsTheModelOnceAndDoesNotRepairExplanatoryOrMalformedOutput() {
        AtomicInteger calls = new AtomicInteger();
        DynamicPlanningModelOperations model = (system, user) -> {
            calls.incrementAndGet();
            return "```json\n" + VALID_PLAN + "\n```";
        };

        assertThat(planner(model).plan(
                new DynamicExecutionContext("owner", "Java", "request"), descriptors()))
                .isEqualTo(DynamicPlanParseResult.failure("INVALID_PLAN_JSON"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void containsModelFailuresWithoutLeakingTheirMessage() {
        DynamicPlanningModelOperations model = (system, user) -> {
            throw new IllegalStateException("token=private-value");
        };

        assertThat(planner(model).plan(
                new DynamicExecutionContext("owner", "Java", "request"), descriptors()))
                .isEqualTo(DynamicPlanParseResult.failure("PLANNER_UNAVAILABLE"));
    }

    private static LlmDynamicTaskPlanner planner(DynamicPlanningModelOperations model) {
        return new LlmDynamicTaskPlanner(model,
                new StrictDynamicPlanJsonParser(new ObjectMapper(), 32 * 1024, 4 * 1024));
    }

    static List<DynamicCapabilityDescriptor> descriptors() {
        return List.of(
                new DynamicCapabilityDescriptor(
                        "learning.plan.compose", 1, DynamicEffectType.READ_ONLY,
                        Map.of(), Set.of("knowledge.evidence.retrieve"), 2, true),
                new DynamicCapabilityDescriptor(
                        "knowledge.evidence.retrieve", 1, DynamicEffectType.READ_ONLY,
                        Map.of("limit", new DynamicArgumentDefinition(
                                "limit", DynamicArgumentType.INTEGER, false, 4, 1L, 4L)),
                        Set.of(), 2, true));
    }
}
