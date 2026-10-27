package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedLearningPlanPlannerTest {
    private final RuleBasedLearningPlanPlanner planner = new RuleBasedLearningPlanPlanner();

    @ParameterizedTest
    @CsvSource({
            "Java并发,结合我的薄弱项安排训练,profile|compose",
            "Java并发,参考知识库资料安排训练,evidence|compose",
            "Java并发,结合历史表现和参考证据安排训练,profile|evidence|compose",
            "Java并发,安排三天训练,profile|evidence|compose",
            "Java concurrency,personalized from my history,profile|compose",
            "Java concurrency,use knowledge references,evidence|compose"
    })
    void selectsAStableConservativeShape(String topic, String request, String expected) {
        CandidateDynamicTaskPlan first = planner.plan(topic, request);
        CandidateDynamicTaskPlan second = planner.plan(topic, request);

        assertThat(shape(first)).isEqualTo(expected);
        assertThat(second).isEqualTo(first);
        assertThat(first.schemaVersion()).isEqualTo("1");
        assertThat(first.nodes().stream().flatMap(node -> node.arguments().keySet().stream()))
                .doesNotContain("owner", "userId", "prompt", "timeout");
    }

    private static String shape(CandidateDynamicTaskPlan plan) {
        return plan.nodes().stream().map(CandidateDynamicTaskNode::id)
                .collect(Collectors.joining("|"));
    }
}
