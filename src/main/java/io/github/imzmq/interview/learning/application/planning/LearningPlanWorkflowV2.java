package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableWorkflow;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public final class LearningPlanWorkflowV2 implements DurableWorkflow {
    private static final Set<String> INPUT_FIELDS = Set.of("topic", "request");

    private final LearningPlanDynamicPlanningService planning;
    private final DynamicPlanningOperations dynamicPlanning;
    private final LearningPlanWorkflowSnapshotCodec snapshotCodec;
    private final LearningPlanWorkflowOutputCodec outputCodec;
    private final ObjectMapper mapper;
    private final LearningPlanScenarioPolicy policy = new LearningPlanScenarioPolicy();

    LearningPlanWorkflowV2(
            LearningPlanDynamicPlanningService planning,
            DynamicPlanningOperations dynamicPlanning,
            LearningPlanWorkflowSnapshotCodec snapshotCodec,
            LearningPlanWorkflowOutputCodec outputCodec,
            ObjectMapper mapper) {
        this.planning = Objects.requireNonNull(planning);
        this.dynamicPlanning = Objects.requireNonNull(dynamicPlanning);
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec);
        this.outputCodec = Objects.requireNonNull(outputCodec);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public String type() {
        return "LEARNING_PLAN_PREVIEW";
    }

    @Override
    public int version() {
        return 2;
    }

    @Override
    public Prepared prepare(String owner, String inputJson) {
        DynamicExecutionContext context = context(owner, inputJson);
        LearningPlanPreparedGraph prepared = planning.prepare(context);
        return new Prepared(snapshotCodec.encode(prepared), prepared.nodes());
    }

    @Override
    public List<DagNode> restore(String owner, String inputJson, String definitionJson) {
        DynamicExecutionContext context = context(owner, inputJson);
        LearningPlanWorkflowSnapshotCodec.Decoded decoded = snapshotCodec.decode(definitionJson);
        if (!policy.validate(decoded.plan()).isEmpty()) {
            throw mismatch();
        }
        if (!decoded.digest().equals(dynamicPlanning.digest(decoded.plan()))) {
            throw mismatch();
        }
        List<DagNode> compiled = dynamicPlanning.compile(decoded.plan(), context);
        verifyCompiled(decoded, compiled);
        return List.copyOf(compiled);
    }

    private void verifyCompiled(
            LearningPlanWorkflowSnapshotCodec.Decoded decoded,
            List<DagNode> compiled) {
        if (compiled == null || compiled.size() != decoded.plan().nodes().size()) {
            throw mismatch();
        }
        Map<String, ValidatedDynamicTaskNode> persistedById = new HashMap<>();
        decoded.plan().nodes().forEach(node -> persistedById.put(node.id(), node));
        Set<String> compiledIds = new HashSet<>();
        for (DagNode node : compiled) {
            if (node == null || !compiledIds.add(node.id())) throw mismatch();
            ValidatedDynamicTaskNode persisted = persistedById.get(node.id());
            Boolean requireSuccess = decoded.requireSuccess().get(node.id());
            if (persisted == null || requireSuccess == null
                    || !node.dependencies().equals(Set.copyOf(persisted.dependsOn()))
                    || node.requireSuccessfulDependencies() != requireSuccess) {
                throw mismatch();
            }
        }
        if (!compiledIds.equals(persistedById.keySet())
                || !compiledIds.equals(decoded.requireSuccess().keySet())) {
            throw mismatch();
        }
    }

    private DynamicExecutionContext context(String owner, String inputJson) {
        try {
            JsonNode tree = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(inputJson);
            if (tree == null || !tree.isObject() || tree.size() != INPUT_FIELDS.size()) {
                throw new IllegalArgumentException();
            }
            Set<String> fields = new HashSet<>();
            tree.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(INPUT_FIELDS)
                    || !tree.get("topic").isTextual()
                    || !tree.get("request").isTextual()) {
                throw new IllegalArgumentException();
            }
            return new DynamicExecutionContext(
                    Objects.requireNonNull(owner),
                    tree.get("topic").textValue(),
                    tree.get("request").textValue());
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_PREVIEW_INPUT");
        }
    }

    @Override
    public String encodeOutput(String nodeId, Object output) {
        return outputCodec.encodeOutput(nodeId, output);
    }

    @Override
    public Object decodeOutput(String nodeId, String outputJson) {
        return outputCodec.decodeOutput(nodeId, outputJson);
    }

    @Override
    public WorkflowOutcome outcome(DagExecutionResult result) {
        return outputCodec.outcome(result);
    }

    private static IllegalArgumentException mismatch() {
        return new IllegalArgumentException("WORKFLOW_DEFINITION_MISMATCH");
    }
}
