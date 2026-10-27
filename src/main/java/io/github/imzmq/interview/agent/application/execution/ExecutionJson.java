package io.github.imzmq.interview.agent.application.execution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

/** Canonical JSON also survives native MySQL JSON object-key reordering. */
@Component
public final class ExecutionJson {
    private final ObjectMapper mapper;
    public ExecutionJson(ObjectMapper mapper) { this.mapper = mapper; }
    public String check(String json, int bytes) {
        try {
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > bytes) throw new IllegalArgumentException();
            JsonNode tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (tree == null || tree.isNull() || tree.isMissingNode()) throw new IllegalArgumentException();
            String result = mapper.writeValueAsString(canonical(tree));
            if (result.getBytes(StandardCharsets.UTF_8).length > bytes) throw new IllegalArgumentException();
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("INVALID_OR_OVERSIZED_EXECUTION_JSON"); }
    }
    public String write(Object value, int bytes) {
        try { return check(mapper.writeValueAsString(value), bytes); }
        catch (Exception e) { throw new IllegalArgumentException("INVALID_OR_OVERSIZED_EXECUTION_JSON"); }
    }
    private JsonNode canonical(JsonNode tree) {
        if (tree.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            var keys = new TreeSet<String>(); tree.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(tree.get(key))));
            return result;
        }
        if (tree.isArray()) {
            ArrayNode result = mapper.createArrayNode(); tree.forEach(item -> result.add(canonical(item))); return result;
        }
        return tree;
    }
}
