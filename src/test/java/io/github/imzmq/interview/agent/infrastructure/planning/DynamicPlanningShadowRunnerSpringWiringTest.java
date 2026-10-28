package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicPlanningShadowRunnerSpringWiringTest {

    @Test
    void springUsesAutowiredConstructorsForShadowPlannerComponents() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(DynamicPlanningModelOperations.class,
                    () -> (systemPrompt, userPrompt) -> "{\"schemaVersion\":\"1\",\"nodes\":[]}");
            context.registerBean(DynamicPlanObservationOperations.class, NoopObservations::new);
            context.register(LlmDynamicTaskPlanner.class, DynamicPlanningShadowRunner.class);

            context.refresh();

            assertThat(context.getBean(LlmDynamicTaskPlanner.class)).isNotNull();
            assertThat(context.getBean(DynamicPlanningShadowOperations.class))
                    .isInstanceOf(DynamicPlanningShadowRunner.class);
        }
    }

    private static final class NoopObservations implements DynamicPlanObservationOperations {
        @Override public void requested(String plannerType, String plannerVersion) { }
        @Override public void generated(String plannerType, String plannerVersion,
                                        ValidatedDynamicTaskPlan plan, String digest, long durationMs) { }
        @Override public void rejected(String plannerType, String plannerVersion, String errorCode, long durationMs) { }
        @Override public void compiled(String plannerType, String plannerVersion,
                                       ValidatedDynamicTaskPlan plan, String digest, long durationMs) { }
        @Override public void fallback(String plannerType, String plannerVersion, String errorCode) { }
        @Override public void shadowCompared(String ruleDigest, String shadowDigest,
                                             boolean same, int nodeDelta, long durationMs) { }
    }
}
