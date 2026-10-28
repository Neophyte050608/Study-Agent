package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.learning.application.planning.LearningPlanDynamicPlanningProperties;
import io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicPlanningShadowRunnerSpringWiringTest {

    @Test
    void springUsesAutowiredConstructorsForShadowPlannerComponents() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(DynamicPlanningModelOperations.class,
                    () -> (systemPrompt, userPrompt) -> "{\"schemaVersion\":\"1\",\"nodes\":[]}");
            context.registerBean(DynamicPlanObservationOperations.class, NoopObservations::new);
            context.register(DynamicPlanningShadowProperties.class,
                    LlmDynamicTaskPlanner.class, DynamicPlanningShadowRunner.class);

            context.refresh();

            assertThat(context.getBean(LlmDynamicTaskPlanner.class)).isNotNull();
            assertThat(context.getBean(DynamicPlanningShadowOperations.class))
                    .isInstanceOf(DynamicPlanningShadowRunner.class);
        }
    }

    @Test
    void localLiteShadowSwitchWiresBoundedRunnerWithoutCallingModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withSystemProperties("APP_LEARNING_PLAN_DYNAMIC_SHADOW_ENABLED=true")
                .withPropertyValues("spring.profiles.active=local-lite")
                .withUserConfiguration(
                        LearningPlanDynamicPlanningProperties.class,
                        DynamicPlanningShadowProperties.class,
                        LlmDynamicTaskPlanner.class,
                        DynamicPlanningShadowRunner.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DynamicPlanningModelOperations.class, () -> (systemPrompt, userPrompt) -> {
                    modelCalls.incrementAndGet();
                    return "{\"schemaVersion\":\"1\",\"nodes\":[]}";
                })
                .withBean(DynamicPlanObservationOperations.class, NoopObservations::new);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LearningPlanDynamicPlanningProperties.class)
                    .isShadowEnabled()).isTrue();
            DynamicPlanningShadowProperties properties =
                    context.getBean(DynamicPlanningShadowProperties.class);
            assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getWorkers()).isEqualTo(1);
            assertThat(properties.getQueueCapacity()).isEqualTo(8);
            assertThat(context.getBean(DynamicPlanningShadowOperations.class))
                    .isInstanceOf(DynamicPlanningShadowRunner.class);
            assertThat(modelCalls).hasValue(0);
        });
    }

    @Test
    void shadowPropertiesAreBoundAndValidated() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(DynamicPlanningShadowProperties.class);

        runner.withPropertyValues(
                "app.agent.dynamic-planning.shadow.timeout=350ms",
                "app.agent.dynamic-planning.shadow.workers=2",
                "app.agent.dynamic-planning.shadow.queue-capacity=4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DynamicPlanningShadowProperties properties =
                            context.getBean(DynamicPlanningShadowProperties.class);
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofMillis(350));
                    assertThat(properties.getWorkers()).isEqualTo(2);
                    assertThat(properties.getQueueCapacity()).isEqualTo(4);
                });

        runner.withPropertyValues("app.agent.dynamic-planning.shadow.timeout=50ms")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.agent.dynamic-planning.shadow.workers=3")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.agent.dynamic-planning.shadow.queue-capacity=33")
                .run(context -> assertThat(context).hasFailed());
    }

    private static final class NoopObservations implements DynamicPlanObservationOperations {
        @Override public void requested(String plannerType, String plannerVersion) { }
        @Override public void generated(String plannerType, String plannerVersion,
                                        ValidatedDynamicTaskPlan plan, String digest, long durationMs) { }
        @Override public void rejected(String plannerType, String plannerVersion, String errorCode, long durationMs) { }
        @Override public void compiled(String plannerType, String plannerVersion,
                                       ValidatedDynamicTaskPlan plan, String digest, long durationMs) { }
        @Override public void fallback(String plannerType, String plannerVersion, String errorCode) { }
        @Override public void shadowCompared(String plannerType, String plannerVersion,
                                             String ruleDigest, String shadowDigest,
                                             boolean same, int nodeDelta, long durationMs) { }
    }
}
