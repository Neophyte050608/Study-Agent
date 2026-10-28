package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import io.github.imzmq.interview.agent.application.execution.dynamic.DefaultDynamicPlanningOperations;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanEvidenceReader;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanPreviewGenerator;

import java.util.List;

final class LearningPlanPreviewTestFixtures {
    private LearningPlanPreviewTestFixtures() {
    }

    static LearningPlanPreviewService previewService(
            DagExecutionOperations executor,
            LearningProfileOperations profiles,
            LearningPlanEvidenceReader evidenceReader,
            LearningPlanPreviewGenerator generator) {
        LearningPlanNodeOperations operations = new LearningPlanNodeOperations(
                profiles, evidenceReader, generator);
        List<DynamicNodeCapability<?>> capabilities = List.of(
                new LearningPlanProfileCapability(operations),
                new LearningPlanEvidenceCapability(operations),
                new LearningPlanComposeCapability(operations));
        LearningPlanDynamicPlanningService planning = new LearningPlanDynamicPlanningService(
                new LearningPlanDynamicPlanningProperties(),
                new RuleBasedLearningPlanPlanner(),
                new DefaultDynamicPlanningOperations(capabilities),
                org.mockito.Mockito.mock(DynamicPlanObservationOperations.class),
                org.mockito.Mockito.mock(DynamicPlanningShadowOperations.class));
        return new LearningPlanPreviewService(executor, planning);
    }
}
