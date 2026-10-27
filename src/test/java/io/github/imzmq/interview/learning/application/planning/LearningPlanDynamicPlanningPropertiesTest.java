package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningPlanDynamicPlanningPropertiesTest {
    @Test
    void defaultsAreFixedAndBounded() {
        LearningPlanDynamicPlanningProperties properties = new LearningPlanDynamicPlanningProperties();

        properties.validate();

        assertThat(properties.getMode())
                .isEqualTo(LearningPlanDynamicPlanningProperties.Mode.FIXED);
        assertThat(properties.toLimits())
                .isEqualTo(new DynamicPlanningLimits(8, 4, 4, 4, 10, 20));
        assertThat(properties.getPlannerTimeoutMillis()).isEqualTo(2000);
        assertThat(properties.getMaxPlanBytes()).isEqualTo(32768);
    }

    @Test
    void rejectsLimitsOutsideThePhaseOneEnvelope() {
        Stream.<Consumer<LearningPlanDynamicPlanningProperties>>of(
                properties -> properties.setMaxNodes(1),
                properties -> properties.setMaxNodes(9),
                properties -> properties.setMaxDepth(0),
                properties -> properties.setMaxDepth(5),
                properties -> properties.setMaxDependenciesPerNode(0),
                properties -> properties.setMaxDependenciesPerNode(5),
                properties -> properties.setMaxFanOut(0),
                properties -> properties.setMaxFanOut(5),
                properties -> properties.setMaxEstimatedCost(0),
                properties -> properties.setMaxEstimatedCost(11),
                properties -> properties.setMaxIssues(0),
                properties -> properties.setMaxIssues(21),
                properties -> properties.setPlannerTimeoutMillis(99),
                properties -> properties.setPlannerTimeoutMillis(3001),
                properties -> properties.setMaxPlanBytes(1023),
                properties -> properties.setMaxPlanBytes(32769))
                .forEach(mutator -> {
                    LearningPlanDynamicPlanningProperties properties =
                            new LearningPlanDynamicPlanningProperties();
                    mutator.accept(properties);
                    assertThatThrownBy(properties::validate)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("INVALID_DYNAMIC_PLANNING_LIMITS");
                });
    }
}
