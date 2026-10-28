package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningPlanWorkflowSnapshotCodecTest {
    private static final String DIGEST = "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LearningPlanWorkflowSnapshotCodec codec =
            new LearningPlanWorkflowSnapshotCodec(mapper);

    @Test
    void canonicalRoundTripPreservesTheSemanticPlanAndSuccessPolicy() throws Exception {
        LearningPlanPreparedGraph prepared = preparedGraph();

        String encoded = codec.encode(prepared);
        LearningPlanWorkflowSnapshotCodec.Decoded decoded = codec.decode(encoded);
        JsonNode root = mapper.readTree(encoded);

        assertThat(fieldNames(root)).containsExactlyInAnyOrder(
                "schemaVersion", "workflowType", "workflowVersion", "scenarioId",
                "planSource", "fallbackCode", "planDigest", "nodes");
        assertThat(fieldNames(root.get("nodes").get(0))).containsExactlyInAnyOrder(
                "id", "capabilityId", "capabilityVersion", "dependsOn",
                "arguments", "requireSuccess");
        assertThat(root.get("nodes").get(0).get("id").textValue()).isEqualTo("compose");
        assertThat(decoded.plan().schemaVersion()).isEqualTo("1");
        assertThat(decoded.plan().scenarioId()).isEqualTo("learning-plan-preview");
        assertThat(decoded.plan().estimatedCost()).isZero();
        assertThat(decoded.plan().nodes()).extracting(ValidatedDynamicTaskNode::id)
                .containsExactly("compose", "profile");
        assertThat(decoded.plan().nodes()).containsExactlyInAnyOrderElementsOf(prepared.plan().nodes());
        assertThat(decoded.digest()).isEqualTo(DIGEST);
        assertThat(decoded.source()).isEqualTo(LearningPlanPreparedGraph.Source.RULES);
        assertThat(decoded.fallbackCode()).isNull();
        assertThat(decoded.requireSuccess()).containsExactlyInAnyOrderEntriesOf(
                Map.of("compose", false, "profile", true));
        assertThat(codec.encode(prepared)).isEqualTo(encoded);
    }

    @ParameterizedTest(name = "rejects malformed snapshot {index}")
    @MethodSource("malformedDefinitions")
    void rejectsMalformedDefinitionsWithoutLeakingParserDetails(String definition) {
        assertThatThrownBy(() -> codec.decode(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("WORKFLOW_DEFINITION_INVALID");
    }

    private static Stream<String> malformedDefinitions() {
        String valid = validDefinition();
        return Stream.of(
                valid.replace("\"planDigest\":\"" + DIGEST + "\"",
                        "\"planDigest\":\"" + DIGEST + "\",\"planDigest\":\"" + DIGEST + "\""),
                valid + " true",
                valid.replace("\"nodes\":", "\"unknown\":1,\"nodes\":"),
                valid.replace("\"requireSuccess\":true", "\"unknown\":1,\"requireSuccess\":true"),
                valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                valid.replace("LEARNING_PLAN_PREVIEW", "OTHER"),
                valid.replace("\"workflowVersion\":2", "\"workflowVersion\":1"),
                valid.replace("learning-plan-preview", "other-scenario"),
                valid.replace("\"planSource\":\"RULES\",\"fallbackCode\":null",
                        "\"planSource\":\"RULES\",\"fallbackCode\":\"PLAN_EMPTY\""),
                valid.replace("\"planSource\":\"RULES\",\"fallbackCode\":null",
                        "\"planSource\":\"FIXED_FALLBACK\",\"fallbackCode\":null"),
                valid.replace("\"id\":\"profile\"", "\"id\":\"compose\""),
                valid.replace("\"id\":\"profile\"", "\"id\":\"Bad id\""),
                valid.replace("\"dependsOn\":[]", "\"dependsOn\":[\"missing\"]"),
                valid.replace("\"arguments\":{}", "\"arguments\":{\"bad\":[1]}"),
                valid.replace("\"arguments\":{}", "\"arguments\":{\"bad\":1.5}"),
                valid.replace("\"arguments\":{}", "\"arguments\":{\"bad\":null}"),
                valid.replace("\"arguments\":{}",
                        "\"arguments\":{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}"),
                valid.substring(0, valid.length() - 1)
                        + ",\"padding\":\"" + "x".repeat(65_536) + "\"}");
    }

    private static LearningPlanPreparedGraph preparedGraph() {
        var profile = new ValidatedDynamicTaskNode(
                "profile", "learning.profile.lookup", 1, List.of(), Map.of());
        var compose = new ValidatedDynamicTaskNode(
                "compose", "learning.plan.compose", 1, List.of("profile"), Map.of());
        var plan = new ValidatedDynamicTaskPlan(
                "1", "learning-plan-preview", List.of(profile, compose), 2);
        List<DagNode> nodes = List.of(
                new DagNode("profile", Set.of(), true, ignored -> "profile"),
                new DagNode("compose", Set.of("profile"), false, ignored -> "plan"));
        return new LearningPlanPreparedGraph(
                plan, DIGEST, nodes, LearningPlanPreparedGraph.Source.RULES, null);
    }

    private static String validDefinition() {
        return """
                {"schemaVersion":2,"workflowType":"LEARNING_PLAN_PREVIEW","workflowVersion":2,
                 "scenarioId":"learning-plan-preview","planSource":"RULES","fallbackCode":null,
                 "planDigest":"%s","nodes":[
                   {"id":"compose","capabilityId":"learning.plan.compose","capabilityVersion":1,
                    "dependsOn":["profile"],"arguments":{},"requireSuccess":false},
                   {"id":"profile","capabilityId":"learning.profile.lookup","capabilityVersion":1,
                    "dependsOn":[],"arguments":{},"requireSuccess":true}]}
                """.formatted(DIGEST).replaceAll("\\s+", "");
    }

    private static Set<String> fieldNames(JsonNode node) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
