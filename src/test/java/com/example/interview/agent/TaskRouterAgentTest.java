package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerRegistry;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.IntentTreeRoutingService;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.PromptManager;
import com.example.interview.service.RAGObservabilityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskRouterAgentTest {

    @Test
    void shouldDispatchExplicitTaskTypeThroughRegistryHandler() {
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.LEARNING_PLAN;
            }

            @Override
            public String receiverName() {
                return "NoteAgent";
            }

            @Override
            public Object handle(TaskRequest request) {
                return Map.of("handled", request.payload().get("query"));
            }
        };

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                mock(IntentTreeRoutingService.class),
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of(handler))
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.LEARNING_PLAN,
                Map.of("query", "java basics"),
                Map.of("traceId", "trace-1")
        ));

        assertEquals(true, response.success());
        assertEquals(Map.of("handled", "java basics"), response.data());
    }

    @Test
    void shouldPublishObservabilityForExplicitTaskDispatch() {
        RAGObservabilityService ragObservabilityService = mock(RAGObservabilityService.class);
        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.KNOWLEDGE_QA;
            }

            @Override
            public String receiverName() {
                return "KnowledgeQaAgent";
            }

            @Override
            public Object handle(TaskRequest request) {
                return "ok";
            }
        };

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                mock(IntentTreeRoutingService.class),
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                ragObservabilityService,
                new TaskHandlerRegistry(List.of(handler))
        );

        taskRouterAgent.dispatch(new TaskRequest(
                TaskType.KNOWLEDGE_QA,
                Map.of("query", "what is redis"),
                Map.of("traceId", "trace-2")
        ));

        verify(ragObservabilityService).startNode(eq("trace-2"), any(), any(), any(), any());
        verify(ragObservabilityService).endNode(any(), any(), any(), any(), any());
    }
}
