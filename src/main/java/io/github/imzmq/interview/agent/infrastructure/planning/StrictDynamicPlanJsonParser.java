package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class StrictDynamicPlanJsonParser {
    private static final String INVALID_PLAN_JSON = "INVALID_PLAN_JSON";
    private static final String PLAN_TOO_LARGE = "PLAN_TOO_LARGE";

    private final ObjectMapper mapper;
    private final int maxPlanBytes;
    private final int maxArgumentsBytes;

    public StrictDynamicPlanJsonParser(
            ObjectMapper injectedObjectMapper,
            int maxPlanBytes,
            int maxArgumentsBytes) {
        if (maxPlanBytes <= 0 || maxArgumentsBytes <= 0) {
            throw new IllegalArgumentException("JSON size limits must be positive");
        }
        this.mapper = Objects.requireNonNull(injectedObjectMapper, "Object mapper is required").copy();
        this.mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.maxPlanBytes = maxPlanBytes;
        this.maxArgumentsBytes = maxArgumentsBytes;
    }

    public DynamicPlanParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DynamicPlanParseResult.failure(INVALID_PLAN_JSON);
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > maxPlanBytes) {
            return DynamicPlanParseResult.failure(PLAN_TOO_LARGE);
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return DynamicPlanParseResult.failure(INVALID_PLAN_JSON);
        }

        try {
            CandidateDynamicTaskPlan plan = mapper.readValue(trimmed, CandidateDynamicTaskPlan.class);
            if (plan == null) {
                return DynamicPlanParseResult.failure(INVALID_PLAN_JSON);
            }
            for (CandidateDynamicTaskNode node : plan.nodes()) {
                if (node == null) {
                    return DynamicPlanParseResult.failure(INVALID_PLAN_JSON);
                }
                if (mapper.writeValueAsBytes(node.arguments()).length > maxArgumentsBytes) {
                    return DynamicPlanParseResult.failure(PLAN_TOO_LARGE);
                }
            }
            return DynamicPlanParseResult.success(plan);
        } catch (Exception ignored) {
            return DynamicPlanParseResult.failure(INVALID_PLAN_JSON);
        }
    }
}
