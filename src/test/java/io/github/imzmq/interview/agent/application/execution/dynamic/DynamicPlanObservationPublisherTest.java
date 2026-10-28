package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.observability.application.contract.OperationalObservationPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DynamicPlanObservationPublisherTest {

    @Test
    void publishesOnlyBoundedStructuralAttributes() {
        RecordingPublisher recorder = new RecordingPublisher();
        DynamicPlanObservationPublisher observer = new DynamicPlanObservationPublisher(recorder);
        ValidatedDynamicTaskPlan plan = plan();

        observer.compiled("rules", "v1", plan, "digest", 12L);

        RecordedEvent event = recorder.single();
        assertThat(event.eventType()).isEqualTo("dynamic_plan.compiled");
        assertThat(event.category()).isEqualTo("agent-planning");
        assertThat(event.name()).isEqualTo("learning-plan-preview");
        assertThat(event.attributes()).containsOnlyKeys(
                "scenario", "plannerType", "plannerVersion", "schemaVersion",
                "nodeCount", "depth", "capabilities", "planDigest", "durationMs");
        assertThat(event.attributes())
                .containsEntry("scenario", "learning-plan-preview")
                .containsEntry("nodeCount", 2)
                .containsEntry("depth", 2)
                .containsEntry("capabilities", "knowledge.evidence.retrieve,learning.plan.compose");
        assertThat(event.attributes().toString())
                .doesNotContain("secret topic", "raw request", "prompt", "owner", "arguments");
    }

    @Test
    void publishesAllLifecycleEventsWithStableFieldsAndNormalizesUnknownErrors() {
        RecordingPublisher recorder = new RecordingPublisher();
        DynamicPlanObservationPublisher observer = new DynamicPlanObservationPublisher(recorder);
        ValidatedDynamicTaskPlan plan = plan();

        observer.requested("rules", "v1");
        observer.generated("rules", "v1", plan, "rule-digest", 3L);
        observer.rejected("rules", "v1", "private failure: token=secret", 4L);
        observer.fallback("rules", "v1", "PLAN_COMPILE_FAILED");
        observer.shadowCompared("llm-shadow", "v1", "rule-digest", "shadow-digest", false, -1, 5L);

        assertThat(recorder.events).extracting(RecordedEvent::eventType).containsExactly(
                "dynamic_plan.requested", "dynamic_plan.generated", "dynamic_plan.rejected",
                "dynamic_plan.fallback", "dynamic_plan.shadow_compared");
        assertThat(recorder.events.get(0).attributes())
                .containsExactlyInAnyOrderEntriesOf(Map.of("plannerType", "rules", "plannerVersion", "v1"));
        assertThat(recorder.events.get(2).attributes())
                .containsEntry("errorCode", "PLANNING_FAILED")
                .containsEntry("durationMs", 4L);
        assertThat(recorder.events.get(3).attributes())
                .containsEntry("errorCode", "PLAN_COMPILE_FAILED")
                .containsEntry("fallbackTarget", "fixed");
        assertThat(recorder.events.get(4).attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "plannerType", "llm-shadow",
                "plannerVersion", "v1",
                "ruleDigest", "rule-digest",
                "shadowDigest", "shadow-digest",
                "same", false,
                "nodeDelta", -1,
                "durationMs", 5L));
        assertThat(recorder.events.toString()).doesNotContain("token=secret");
    }

    @Test
    void preservesStableShadowTimeoutAndOverloadCodes() {
        RecordingPublisher recorder = new RecordingPublisher();
        DynamicPlanObservationPublisher observer = new DynamicPlanObservationPublisher(recorder);

        observer.rejected("llm-shadow", "v1", "SHADOW_TIMEOUT", 80L);
        observer.rejected("llm-shadow", "v1", "SHADOW_OVERLOADED", 0L);

        assertThat(recorder.events).extracting(event -> event.attributes().get("errorCode"))
                .containsExactly("SHADOW_TIMEOUT", "SHADOW_OVERLOADED");
    }

    @Test
    void sortsAndBoundsCapabilityIds() {
        RecordingPublisher recorder = new RecordingPublisher();
        DynamicPlanObservationPublisher observer = new DynamicPlanObservationPublisher(recorder);
        String longCapability = "z".repeat(100);
        ValidatedDynamicTaskPlan plan = new ValidatedDynamicTaskPlan("1", "learning-plan-preview", List.of(
                new ValidatedDynamicTaskNode("b", longCapability, 1, List.of(), Map.of()),
                new ValidatedDynamicTaskNode("a", "a.capability", 1, List.of(), Map.of())), 2);

        observer.generated("rules", "v1", plan, "digest", -10L);

        String capabilities = String.valueOf(recorder.single().attributes().get("capabilities"));
        assertThat(capabilities).startsWith("a.capability,").hasSizeLessThanOrEqualTo(120);
        assertThat(recorder.single().attributes()).containsEntry("durationMs", 0L);
    }

    @Test
    void observationFailureNeverChangesPlanningBehavior() {
        DynamicPlanObservationPublisher observer = new DynamicPlanObservationPublisher(
                (eventType, category, name, status, attributes) -> {
                    throw new IllegalStateException("external telemetry unavailable");
                });

        assertThatCode(() -> observer.requested("rules", "v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> observer.compiled("rules", "v1", plan(), "digest", 1L))
                .doesNotThrowAnyException();
    }

    private static ValidatedDynamicTaskPlan plan() {
        return new ValidatedDynamicTaskPlan("1", "learning-plan-preview", List.of(
                new ValidatedDynamicTaskNode("evidence", "knowledge.evidence.retrieve", 1,
                        List.of(), Map.of("query", "secret topic", "request", "raw request")),
                new ValidatedDynamicTaskNode("compose", "learning.plan.compose", 1,
                        List.of("evidence"), Map.of("prompt", "private prompt", "owner", "owner"))), 5);
    }

    private record RecordedEvent(
            String eventType,
            String category,
            String name,
            String status,
            Map<String, Object> attributes) {
    }

    private static final class RecordingPublisher implements OperationalObservationPublisher {
        private final List<RecordedEvent> events = new ArrayList<>();

        @Override
        public void publish(String eventType, String category, String name, String status,
                            Map<String, ?> attributes) {
            Map<String, Object> copied = new java.util.LinkedHashMap<>();
            attributes.forEach(copied::put);
            events.add(new RecordedEvent(eventType, category, name, status, copied));
        }

        private RecordedEvent single() {
            assertThat(events).hasSize(1);
            return events.getFirst();
        }
    }
}
