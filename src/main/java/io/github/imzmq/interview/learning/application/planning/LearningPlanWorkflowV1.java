package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableWorkflow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public final class LearningPlanWorkflowV1 implements DurableWorkflow {
    private final LearningPlanFixedNodeFactory fixedNodeFactory;
    private final LearningPlanWorkflowOutputCodec outputCodec;
    private final ObjectMapper mapper;

    public LearningPlanWorkflowV1(
            LearningPlanFixedNodeFactory fixedNodeFactory,
            LearningPlanWorkflowOutputCodec outputCodec,
            ObjectMapper mapper) {
        this.fixedNodeFactory = fixedNodeFactory;
        this.outputCodec = outputCodec;
        this.mapper = mapper;
    }

    public String type() {
        return "LEARNING_PLAN_PREVIEW";
    }

    public int version() {
        return 1;
    }

    public Prepared prepare(String owner, String inputJson) {
        List<DagNode> nodes = nodesForInput(owner, inputJson);
        try {
            return new Prepared(mapper.writeValueAsString(definition(nodes)), nodes);
        } catch (Exception exception) {
            throw new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");
        }
    }

    public List<DagNode> restore(String owner, String inputJson, String definitionJson) {
        List<DagNode> nodes = nodesForInput(owner, inputJson);
        List<NodeDefinition> expected = definition(nodes);
        List<NodeDefinition> restored = decodeDefinition(definitionJson);
        if (!expected.equals(restored)) {
            throw new IllegalArgumentException("WORKFLOW_DEFINITION_MISMATCH");
        }
        return nodes;
    }

    private List<DagNode> nodesForInput(String owner, String inputJson) {
        try {
            var tree = mapper.readTree(inputJson);
            if (!tree.isObject() || tree.size() != 2
                    || !tree.path("topic").isTextual() || !tree.path("request").isTextual()) {
                throw new IllegalArgumentException();
            }
            return fixedNodeFactory.nodes(
                    owner, tree.get("topic").textValue(), tree.get("request").textValue());
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_PREVIEW_INPUT");
        }
    }

    private List<NodeDefinition> definition(List<DagNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparing(DagNode::id))
                .map(node -> new NodeDefinition(node.id(),
                        node.dependencies().stream().sorted().toList(),
                        node.requireSuccessfulDependencies()))
                .toList();
    }

    private List<NodeDefinition> decodeDefinition(String definitionJson) {
        try {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(definitionJson);
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > 64) {
                throw new IllegalArgumentException();
            }
            List<NodeDefinition> result = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isObject() || item.size() != 3
                        || !item.has("id") || !item.get("id").isTextual()
                        || !item.has("dependencies") || !item.get("dependencies").isArray()
                        || !item.has("requireSuccess") || !item.get("requireSuccess").isBoolean()) {
                    throw new IllegalArgumentException();
                }
                Set<String> fields = new HashSet<>();
                item.fieldNames().forEachRemaining(fields::add);
                if (!fields.equals(Set.of("id", "dependencies", "requireSuccess"))) {
                    throw new IllegalArgumentException();
                }
                List<String> dependencies = new ArrayList<>();
                for (JsonNode dependency : item.get("dependencies")) {
                    if (!dependency.isTextual()) throw new IllegalArgumentException();
                    dependencies.add(dependency.textValue());
                }
                result.add(new NodeDefinition(item.get("id").textValue(),
                        List.copyOf(dependencies), item.get("requireSuccess").booleanValue()));
            }
            result.sort(Comparator.comparing(NodeDefinition::id));
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");
        }
    }

    private record NodeDefinition(String id, List<String> dependencies, boolean requireSuccess) { }

    public String encodeOutput(String nodeId, Object output) {
        return outputCodec.encodeOutput(nodeId, output);
    }

    public Object decodeOutput(String nodeId, String outputJson) {
        return outputCodec.decodeOutput(nodeId, outputJson);
    }

    public WorkflowOutcome outcome(DagExecutionResult result) {
        return outputCodec.outcome(result);
    }
}
