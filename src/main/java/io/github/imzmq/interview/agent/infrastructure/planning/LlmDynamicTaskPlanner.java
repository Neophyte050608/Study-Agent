package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentDefinition;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public final class LlmDynamicTaskPlanner {
    private static final String SYSTEM_PROMPT = """
            Return one JSON object only. Do not use markdown.
            Use schemaVersion "1".
            Use only the supplied capability IDs and allowed arguments.
            Do not emit user identity, URL, file path, class, Bean, method, SQL, shell, tool, timeout, retry, token, or infrastructure settings.
            The object schema is {"schemaVersion":"1","nodes":[{"id":"lowercase-id","capability":"stable.id","dependsOn":[],"arguments":{}}]}.
            """;
    private static final int MAX_TOPIC_LENGTH = 200;
    private static final int MAX_REQUEST_LENGTH = 2_000;

    private final DynamicPlanningModelOperations model;
    private final StrictDynamicPlanJsonParser parser;

    public LlmDynamicTaskPlanner(DynamicPlanningModelOperations model, ObjectMapper objectMapper) {
        this(model, new StrictDynamicPlanJsonParser(objectMapper, 32 * 1024, 4 * 1024));
    }

    public LlmDynamicTaskPlanner(
            DynamicPlanningModelOperations model,
            StrictDynamicPlanJsonParser parser) {
        this.model = Objects.requireNonNull(model);
        this.parser = Objects.requireNonNull(parser);
    }

    public DynamicPlanParseResult plan(
            DynamicExecutionContext context,
            List<DynamicCapabilityDescriptor> descriptors) {
        Objects.requireNonNull(context, "Dynamic execution context is required");
        List<DynamicCapabilityDescriptor> snapshot = List.copyOf(
                Objects.requireNonNull(descriptors, "Capability descriptors are required"));
        String userPrompt = "topic: " + bounded(context.topic(), MAX_TOPIC_LENGTH)
                + "\nrequest: " + bounded(context.request(), MAX_REQUEST_LENGTH)
                + "\nschemaVersion: 1\ncapabilities:\n"
                + snapshot.stream()
                .sorted(Comparator.comparing(DynamicCapabilityDescriptor::capabilityId)
                        .thenComparingInt(DynamicCapabilityDescriptor::capabilityVersion))
                .map(LlmDynamicTaskPlanner::describe)
                .collect(Collectors.joining("\n"));
        try {
            return parser.parse(model.generatePlan(SYSTEM_PROMPT, userPrompt));
        } catch (RuntimeException ignored) {
            return DynamicPlanParseResult.failure("PLANNER_UNAVAILABLE");
        }
    }

    private static String describe(DynamicCapabilityDescriptor descriptor) {
        String arguments = descriptor.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> describe(entry.getValue()))
                .collect(Collectors.joining(",", "[", "]"));
        String dependencies = descriptor.allowedDependencyCapabilities().stream()
                .sorted()
                .collect(Collectors.joining(",", "[", "]"));
        return "- id=" + descriptor.capabilityId()
                + "; version=" + descriptor.capabilityVersion()
                + "; effect=" + descriptor.effectType()
                + "; arguments=" + arguments
                + "; allowedDependencies=" + dependencies;
    }

    private static String describe(DynamicArgumentDefinition definition) {
        return definition.name() + ":" + definition.type()
                + ":required=" + definition.required()
                + ":min=" + value(definition.minimum())
                + ":max=" + value(definition.maximum())
                + ":default=" + value(definition.defaultValue());
    }

    private static String value(Object value) {
        return value == null ? "none" : String.valueOf(value);
    }

    private static String bounded(String value, int maxLength) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
