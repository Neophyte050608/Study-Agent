package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.execution.DurableWorkflowRegistry;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;

import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.application.contract.extension.AgentTaskPromptRenderer;
import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.agent.application.contract.task.TaskResponse;
import io.github.imzmq.interview.agent.application.contract.task.TaskType;
import io.github.imzmq.interview.agent.application.execution.FixedDagExecutor;
import io.github.imzmq.interview.agent.application.execution.dynamic.DefaultDynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.execution.dynamic.DynamicPlanObservationPublisher;
import io.github.imzmq.interview.agent.router.TaskHandlerRegistry;
import io.github.imzmq.interview.agent.runtime.TaskRouterAgent;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.planning.LexicalLearningPlanEvidenceReader;
import io.github.imzmq.interview.knowledge.application.planning.ModelLearningPlanPreviewGenerator;
import io.github.imzmq.interview.learning.application.agent.handler.LearningPlanPreviewTaskHandler;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreview;
import io.github.imzmq.interview.learning.application.contract.result.TrainingProfileSnapshot;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.observability.application.trace.RAGObservabilityService;
import io.github.imzmq.interview.observability.application.contract.OperationalObservationPublisher;
import io.github.imzmq.interview.routing.domain.IntentPreFilter;
import io.github.imzmq.interview.routing.application.IntentTreeRoutingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningPlanPreviewWiringTest {
    @Test
    void springDiscoversPreviewHandlerAndDispatchKeepsRealSuccessDegradationAndFailure() {
        LearningProfileOperations profiles = mock(LearningProfileOperations.class);
        LexicalIndexService lexical = mock(LexicalIndexService.class);
        RoutingChatService routing = mock(RoutingChatService.class);
        when(profiles.snapshot("owner")).thenReturn(new TrainingProfileSnapshot(List.of(), List.of(),
                "UP", List.of("Java"), List.of(), 1, "now"));
        when(lexical.search("Java", 4)).thenReturn(List.of(new Document("doc", "Java evidence", Map.of())));
        when(routing.callWithFirstPacketProbeSupplier(any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn("learning plan");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LearningProfileOperations.class, () -> profiles);
            context.registerBean(LexicalIndexService.class, () -> lexical);
            context.registerBean(RoutingChatService.class, () -> routing);
            context.registerBean(OperationalObservationPublisher.class,
                    () -> (eventType, category, name, status, attributes) -> { });
            context.registerBean(DynamicPlanningShadowOperations.class,
                    () -> mock(DynamicPlanningShadowOperations.class));
            context.register(FixedDagExecutor.class, LearningPlanNodeOperations.class,
                    LearningPlanFixedNodeFactory.class, LearningPlanDynamicPlanningProperties.class,
                    RuleBasedLearningPlanPlanner.class, LearningPlanProfileCapability.class,
                    LearningPlanEvidenceCapability.class, LearningPlanComposeCapability.class,
                    DefaultDynamicPlanningOperations.class,
                    DynamicPlanObservationPublisher.class,
                    LearningPlanDynamicPlanningService.class, LearningPlanPreviewService.class,
                    LearningPlanPreviewTaskHandler.class, LexicalLearningPlanEvidenceReader.class,
                    ModelLearningPlanPreviewGenerator.class, TaskHandlerRegistry.class);
            context.refresh();
            TaskRouterAgent router = new TaskRouterAgent(mock(AgentTaskPromptRenderer.class),
                    mock(IntentTreeRoutingService.class), mock(IntentPreFilter.class), mock(A2ABus.class),
                    routing, mock(RAGObservabilityService.class), context.getBean(TaskHandlerRegistry.class));
            TaskRequest request = new TaskRequest(TaskType.LEARNING_PLAN_PREVIEW,
                    Map.of("topic", "Java", "userId", "untrusted-user"), Map.of("userId", "owner"));
            TaskResponse complete = router.dispatch(request);
            assertTrue(complete.success());
            assertEquals("COMPLETED", complete.message());
            assertEquals("learning plan", ((LearningPlanPreview) complete.data()).plan());

            when(lexical.search("Java", 4)).thenThrow(new IllegalStateException("private detail"));
            TaskResponse degraded = router.dispatch(request);
            assertTrue(degraded.success());
            assertEquals("DEGRADED", degraded.message());
            assertEquals(List.of("EVIDENCE_UNAVAILABLE"), ((LearningPlanPreview) degraded.data()).degradations());
            assertTrue(((LearningPlanPreview) degraded.data()).evidence().isEmpty());

            when(routing.callWithFirstPacketProbeSupplier(any(), anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(new IllegalStateException("private detail"));
            TaskResponse failed = router.dispatch(request);
            assertFalse(failed.success());
            assertEquals("FAILED", failed.message());
            assertEquals("", ((LearningPlanPreview) failed.data()).plan());
            assertFalse(failed.toString().contains("private detail"));
            verify(profiles, times(3)).snapshot("owner");
            verifyNoMoreInteractions(profiles);
        }
    }
    @Test
    void springWiresRequiredShadowOperations() {
        DynamicPlanningShadowOperations shadow = mock(DynamicPlanningShadowOperations.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DynamicPlanningShadowOperations.class, () -> shadow);
            context.registerBean(DynamicPlanObservationOperations.class,
                    () -> mock(DynamicPlanObservationOperations.class));
            context.registerBean(LearningPlanNodeOperations.class,
                    () -> mock(LearningPlanNodeOperations.class));
            context.register(LearningPlanDynamicPlanningProperties.class,
                    LearningPlanFixedNodeFactory.class, RuleBasedLearningPlanPlanner.class,
                    DefaultDynamicPlanningOperations.class, LearningPlanDynamicPlanningService.class);
            context.refresh();

            assertNotNull(context.getBean(LearningPlanDynamicPlanningService.class));
        }
    }

    @Test
    void springRegistersHistoricalAndCurrentLearningPlanWorkflows() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(LearningPlanNodeOperations.class,
                    () -> mock(LearningPlanNodeOperations.class));
            context.registerBean(LearningPlanPreviewService.class,
                    () -> mock(LearningPlanPreviewService.class));
            context.registerBean(LearningPlanDynamicPlanningService.class,
                    () -> mock(LearningPlanDynamicPlanningService.class));
            context.registerBean(DynamicPlanningOperations.class,
                    () -> mock(DynamicPlanningOperations.class));
            context.register(LearningPlanFixedNodeFactory.class,
                    LearningPlanWorkflowOutputCodec.class,
                    LearningPlanWorkflowSnapshotCodec.class,
                    LearningPlanWorkflowV1.class,
                    LearningPlanWorkflowV2.class,
                    DurableWorkflowRegistry.class);
            context.refresh();

            DurableWorkflowRegistry registry = context.getBean(DurableWorkflowRegistry.class);
            assertInstanceOf(LearningPlanWorkflowV1.class,
                    registry.require("LEARNING_PLAN_PREVIEW", 1));
            assertInstanceOf(LearningPlanWorkflowV2.class,
                    registry.require("LEARNING_PLAN_PREVIEW", 2));
        }
    }

}
