package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Component
final class LearningPlanWorkflowSnapshotCodec {
    private static final int MAX_DEFINITION_BYTES = 65_536;
    private static final int MAX_NODES = 64;
    private static final int MAX_DEPENDENCIES = 64;
    private static final int MAX_ARGUMENT_ENTRIES = 32;
    private static final int MAX_ARGUMENT_DEPTH = 4;
    private static final int MAX_ARGUMENT_STRING_LENGTH = 4_096;
    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9.-]{0,119}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "workflowType", "workflowVersion", "scenarioId",
            "planSource", "fallbackCode", "planDigest", "nodes");
    private static final Set<String> NODE_FIELDS = Set.of(
            "id", "capabilityId", "capabilityVersion",
            "dependsOn", "arguments", "requireSuccess");
    private static final Set<String> STABLE_FALLBACK_CODES = Set.of(
            "UNSUPPORTED_SCHEMA_VERSION", "PLAN_EMPTY", "PLAN_TOO_LARGE",
            "INVALID_NODE_ID", "DUPLICATE_NODE_ID", "UNKNOWN_CAPABILITY",
            "INVALID_ARGUMENTS", "UNKNOWN_DEPENDENCY", "CYCLIC_DEPENDENCY",
            "MAX_DEPTH_EXCEEDED", "MAX_DEPENDENCIES_EXCEEDED", "MAX_FAN_OUT_EXCEEDED",
            "DISALLOWED_EFFECT", "INCOMPATIBLE_DEPENDENCY", "MISSING_FINAL_OUTPUT",
            "SCENARIO_POLICY_VIOLATION", "PLAN_BUDGET_EXCEEDED", "PLAN_COMPILE_FAILED",
            "PLANNER_TIMEOUT", "PLANNER_UNAVAILABLE", "PLANNING_FAILED");

    private final ObjectMapper mapper;

    LearningPlanWorkflowSnapshotCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    String encode(LearningPlanPreparedGraph prepared) {
        try {
            Objects.requireNonNull(prepared, "Prepared graph is required");
            Map<String, DagNode> compiledById = indexCompiled(prepared.nodes());
            List<Map<String, Object>> nodes = prepared.plan().nodes().stream()
                    .sorted(Comparator.comparing(ValidatedDynamicTaskNode::id))
                    .map(node -> encodeNode(node, compiledById.remove(node.id())))
                    .toList();
            if (!compiledById.isEmpty()) {
                throw invalid();
            }
            validateSource(prepared.source(), prepared.fallbackCode());
            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 2);
            root.put("workflowType", "LEARNING_PLAN_PREVIEW");
            root.put("workflowVersion", 2);
            root.put("scenarioId", "learning-plan-preview");
            root.put("planSource", prepared.source().name());
            root.put("fallbackCode", prepared.fallbackCode());
            root.put("planDigest", prepared.digest());
            root.put("nodes", nodes);
            String encoded = mapper.writeValueAsString(root);
            decode(encoded);
            return encoded;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    Decoded decode(String definitionJson) {
        try {
            if (definitionJson == null || definitionJson.isBlank()
                    || definitionJson.getBytes(StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) {
                throw invalid();
            }
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(definitionJson);
            requireObject(root, ROOT_FIELDS);
            requireInt(root, "schemaVersion", 2);
            requireText(root, "workflowType", "LEARNING_PLAN_PREVIEW");
            requireInt(root, "workflowVersion", 2);
            requireText(root, "scenarioId", "learning-plan-preview");
            LearningPlanPreparedGraph.Source source = parseSource(root.get("planSource"));
            String fallbackCode = nullableText(root.get("fallbackCode"));
            validateSource(source, fallbackCode);
            String digest = text(root.get("planDigest"));
            if (!DIGEST.matcher(digest).matches()) {
                throw invalid();
            }
            JsonNode nodeArray = root.get("nodes");
            if (!nodeArray.isArray() || nodeArray.isEmpty() || nodeArray.size() > MAX_NODES) {
                throw invalid();
            }
            List<ValidatedDynamicTaskNode> nodes = new ArrayList<>();
            LinkedHashMap<String, Boolean> requireSuccess = new LinkedHashMap<>();
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode item : nodeArray) {
                requireObject(item, NODE_FIELDS);
                String id = text(item.get("id"));
                String capabilityId = text(item.get("capabilityId"));
                int capabilityVersion = positiveInt(item.get("capabilityVersion"));
                if (!NODE_ID.matcher(id).matches() || !CAPABILITY_ID.matcher(capabilityId).matches()
                        || !ids.add(id)) {
                    throw invalid();
                }
                List<String> dependencies = dependencies(item.get("dependsOn"));
                Map<String, Object> arguments = arguments(item.get("arguments"), 1);
                JsonNode success = item.get("requireSuccess");
                if (!success.isBoolean()) {
                    throw invalid();
                }
                nodes.add(new ValidatedDynamicTaskNode(
                        id, capabilityId, capabilityVersion, dependencies, arguments));
                requireSuccess.put(id, success.booleanValue());
            }
            validateTopology(nodes, ids);
            ValidatedDynamicTaskPlan plan = new ValidatedDynamicTaskPlan(
                    "1", "learning-plan-preview", List.copyOf(nodes), 0);
            return new Decoded(plan, digest, source, fallbackCode, Map.copyOf(requireSuccess));
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static Map<String, DagNode> indexCompiled(List<DagNode> nodes) {
        if (nodes == null || nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw invalid();
        }
        Map<String, DagNode> byId = new HashMap<>();
        for (DagNode node : nodes) {
            if (node == null || byId.putIfAbsent(node.id(), node) != null) {
                throw invalid();
            }
        }
        return byId;
    }

    private static Map<String, Object> encodeNode(
            ValidatedDynamicTaskNode node,
            DagNode compiled) {
        if (compiled == null || !Set.copyOf(node.dependsOn()).equals(compiled.dependencies())) {
            throw invalid();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.id());
        result.put("capabilityId", node.capabilityId());
        result.put("capabilityVersion", node.capabilityVersion());
        result.put("dependsOn", node.dependsOn().stream().sorted().toList());
        result.put("arguments", canonicalArguments(node.arguments(), 1));
        result.put("requireSuccess", compiled.requireSuccessfulDependencies());
        return result;
    }

    private static Map<String, Object> canonicalArguments(Map<?, ?> values, int depth) {
        if (values == null || values.size() > MAX_ARGUMENT_ENTRIES || depth > MAX_ARGUMENT_DEPTH) {
            throw invalid();
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > 120) {
                throw invalid();
            }
            sorted.put(key, canonicalValue(entry.getValue(), depth));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Object canonicalValue(Object value, int depth) {
        if (value instanceof Map<?, ?> nested) {
            return canonicalArguments(nested, depth + 1);
        }
        if (value instanceof String text) {
            if (text.length() > MAX_ARGUMENT_STRING_LENGTH) throw invalid();
            return text;
        }
        if (value instanceof Integer || value instanceof Boolean) {
            return value;
        }
        throw invalid();
    }

    private static Map<String, Object> arguments(JsonNode node, int depth) {
        if (!node.isObject() || node.size() > MAX_ARGUMENT_ENTRIES || depth > MAX_ARGUMENT_DEPTH) {
            throw invalid();
        }
        TreeMap<String, Object> result = new TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key.isBlank() || key.length() > 120) throw invalid();
            result.put(key, argumentValue(entry.getValue(), depth));
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static Object argumentValue(JsonNode node, int depth) {
        if (node.isObject()) return arguments(node, depth + 1);
        if (node.isTextual()) {
            if (node.textValue().length() > MAX_ARGUMENT_STRING_LENGTH) throw invalid();
            return node.textValue();
        }
        if (node.isInt()) return node.intValue();
        if (node.isBoolean()) return node.booleanValue();
        throw invalid();
    }

    private static List<String> dependencies(JsonNode node) {
        if (!node.isArray() || node.size() > MAX_DEPENDENCIES) throw invalid();
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode dependency : node) {
            String id = text(dependency);
            if (!NODE_ID.matcher(id).matches() || !unique.add(id)) throw invalid();
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static void validateTopology(
            List<ValidatedDynamicTaskNode> nodes,
            Set<String> ids) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        for (ValidatedDynamicTaskNode node : nodes) {
            indegree.put(node.id(), node.dependsOn().size());
            for (String dependency : node.dependsOn()) {
                if (!ids.contains(dependency) || dependency.equals(node.id())) throw invalid();
                downstream.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node.id());
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            visited++;
            for (String next : downstream.getOrDefault(current, List.of())) {
                if (indegree.compute(next, (ignored, degree) -> degree - 1) == 0) ready.add(next);
            }
        }
        if (visited != nodes.size()) throw invalid();
    }

    private static LearningPlanPreparedGraph.Source parseSource(JsonNode node) {
        String value = text(node);
        try {
            return LearningPlanPreparedGraph.Source.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void validateSource(
            LearningPlanPreparedGraph.Source source,
            String fallbackCode) {
        if (source == null
                || source == LearningPlanPreparedGraph.Source.RULES && fallbackCode != null
                || source == LearningPlanPreparedGraph.Source.FIXED_FALLBACK
                && !STABLE_FALLBACK_CODES.contains(fallbackCode)) {
            throw invalid();
        }
    }

    private static void requireObject(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw invalid();
    }

    private static void requireInt(JsonNode root, String field, int expected) {
        JsonNode value = root.get(field);
        if (value == null || !value.isInt() || value.intValue() != expected) throw invalid();
    }

    private static void requireText(JsonNode root, String field, String expected) {
        if (!expected.equals(text(root.get(field)))) throw invalid();
    }

    private static int positiveInt(JsonNode node) {
        if (node == null || !node.isInt() || node.intValue() < 1) throw invalid();
        return node.intValue();
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static String nullableText(JsonNode node) {
        if (node == null) throw invalid();
        return node.isNull() ? null : text(node);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");
    }

    record Decoded(
            ValidatedDynamicTaskPlan plan,
            String digest,
            LearningPlanPreparedGraph.Source source,
            String fallbackCode,
            Map<String, Boolean> requireSuccess) {
        Decoded {
            requireSuccess = Map.copyOf(requireSuccess);
        }
    }
}
