package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AMessage;
import com.example.interview.agent.router.TaskHandler;
import com.example.interview.agent.router.TaskHandlerRegistry;
import com.example.interview.agent.task.ExecutionMode;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.intent.IntentPreFilter;
import com.example.interview.intent.IntentRoutingDecision;
import com.example.interview.intent.PreFilterResult;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.modelrouting.TimeoutHint;
import com.example.interview.service.IntentTreeRoutingService;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.PromptManager;
import com.example.interview.service.RAGObservabilityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                mock(IntentPreFilter.class),
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
                mock(IntentPreFilter.class),
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

    @Test
    void shouldFallbackToIntentTreeClarificationWhenPrefilterCodingSlotsAreInsufficient() {
        IntentPreFilter intentPreFilter = mock(IntentPreFilter.class);
        IntentTreeRoutingService intentTreeRoutingService = mock(IntentTreeRoutingService.class);
        when(intentPreFilter.filter("刷题")).thenReturn(Optional.of(PreFilterResult.domainOnly("CODING")));
        when(intentTreeRoutingService.enabled()).thenReturn(true);
        when(intentTreeRoutingService.route("刷题", "history", "CODING")).thenReturn(new IntentRoutingDecision(
                false,
                "CODING_PRACTICE",
                0.8,
                "need-clarification",
                Map.of(),
                List.of(),
                true,
                "你想刷什么题？",
                List.of(
                        Map.of("label", "算法题", "value", "ALGORITHM"),
                        Map.of("label", "选择题", "value", "CHOICE")
                )
        ));

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                intentTreeRoutingService,
                intentPreFilter,
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of())
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "刷题"),
                new java.util.HashMap<>(Map.of("traceId", "trace-3", "history", "history"))
        ));

        assertTrue(response.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertEquals(true, data.get("clarification"));
        assertEquals("你想刷什么题？", data.get("question"));
        verify(intentTreeRoutingService).route("刷题", "history", "CODING");
    }

    @Test
    void shouldPublishReplyAndObservabilityForPrefilterDirectReply() {
        IntentPreFilter intentPreFilter = mock(IntentPreFilter.class);
        A2ABus a2aBus = mock(A2ABus.class);
        RAGObservabilityService ragObservabilityService = mock(RAGObservabilityService.class);
        when(intentPreFilter.filter("/help")).thenReturn(Optional.of(PreFilterResult.directReply("help text")));

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                mock(IntentTreeRoutingService.class),
                intentPreFilter,
                a2aBus,
                mock(RoutingChatService.class),
                ragObservabilityService,
                new TaskHandlerRegistry(List.of())
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "/help"),
                new java.util.HashMap<>(Map.of("traceId", "trace-4", "replyTo", "caller-agent"))
        ));

        assertTrue(response.success());
        assertEquals(Map.of("question", "help text"), response.data());
        verify(ragObservabilityService).startNode(eq("trace-4"), any(), any(), any(), eq("Task Dispatch: PRE_FILTER_REPLY"));
        verify(ragObservabilityService).endNode(eq("trace-4"), any(), any(), eq("ok"), eq(null));
        verify(a2aBus).publish(any(A2AMessage.class));
        verify(intentPreFilter).filter("/help");
    }

    @Test
    void shouldReturnGreetingForSimpleGreeting() {
        IntentPreFilter intentPreFilter = new IntentPreFilter();

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                mock(IntentTreeRoutingService.class),
                intentPreFilter,
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of())
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "你好"),
                new java.util.HashMap<>(Map.of("traceId", "trace-greet"))
        ));

        assertTrue(response.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertTrue(((String) data.get("question")).contains("AI 面试官助理"));
    }

    @Test
    void shouldNotRouteToCodingPracticeForNegativeExpression() {
        IntentPreFilter intentPreFilter = new IntentPreFilter();
        IntentTreeRoutingService intentTreeRoutingService = mock(IntentTreeRoutingService.class);
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        PromptManager promptManager = mock(PromptManager.class);
        when(intentTreeRoutingService.enabled()).thenReturn(false);
        when(promptManager.render(eq("task-router"), any())).thenReturn("prompt");
        when(routingChatService.callWithFirstPacketProbeSupplier(
                any(),
                eq("prompt"),
                any(ModelRouteType.class),
                any(TimeoutHint.class),
                any()
        ))
                .thenReturn("{\"taskType\":\"UNKNOWN\"}");

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                promptManager,
                intentTreeRoutingService,
                intentPreFilter,
                mock(A2ABus.class),
                routingChatService,
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of())
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "我不想刷题了想面试"),
                new java.util.HashMap<>(Map.of("traceId", "trace-neg"))
        ));

        assertTrue(response.success());
        verify(intentTreeRoutingService).enabled();
        verify(routingChatService).callWithFirstPacketProbeSupplier(
                any(),
                eq("prompt"),
                any(ModelRouteType.class),
                any(TimeoutHint.class),
                any()
        );
    }

    @Test
    void shouldNotRouteToInterviewStartForNonInterviewPrefix() {
        IntentPreFilter intentPreFilter = new IntentPreFilter();
        IntentTreeRoutingService intentTreeRoutingService = mock(IntentTreeRoutingService.class);
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        PromptManager promptManager = mock(PromptManager.class);
        when(intentTreeRoutingService.enabled()).thenReturn(false);
        when(promptManager.render(eq("task-router"), any())).thenReturn("prompt");
        when(routingChatService.callWithFirstPacketProbeSupplier(
                any(),
                eq("prompt"),
                any(ModelRouteType.class),
                any(TimeoutHint.class),
                any()
        ))
                .thenReturn("{\"taskType\":\"UNKNOWN\"}");

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                promptManager,
                intentTreeRoutingService,
                intentPreFilter,
                mock(A2ABus.class),
                routingChatService,
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of())
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "开始一场会议"),
                new java.util.HashMap<>(Map.of("traceId", "trace-meeting"))
        ));

        assertTrue(response.success());
        verify(intentTreeRoutingService).enabled();
        verify(routingChatService).callWithFirstPacketProbeSupplier(
                any(),
                eq("prompt"),
                any(ModelRouteType.class),
                any(TimeoutHint.class),
                any()
        );
    }

    @Test
    void shouldReturnClearSessionMarkerForClearCommand() {
        IntentPreFilter intentPreFilter = new IntentPreFilter();
        Map<String, Object> context = new java.util.HashMap<>(Map.of("traceId", "trace-clear", "sessionId", "session-1"));

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                mock(IntentTreeRoutingService.class),
                intentPreFilter,
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of())
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "/clear"),
                context
        ));

        assertTrue(response.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertEquals(true, data.get("clearSession"));
        assertEquals(true, context.get("clearSession"));
    }

    @Test
    void shouldDelegateKnowledgeQaForStreamRouteOnlyExecutionMode() {
        TaskHandler handler = mock(TaskHandler.class);
        when(handler.taskType()).thenReturn(TaskType.KNOWLEDGE_QA);

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                mock(IntentTreeRoutingService.class),
                mock(IntentPreFilter.class),
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of(handler))
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.KNOWLEDGE_QA,
                Map.of("query", "给我介绍一下Redis"),
                new java.util.HashMap<>(Map.of(
                        "traceId", "trace-stream-qa",
                        "executionMode", ExecutionMode.STREAM_ROUTE_ONLY.name(),
                        "retrievalMode", "RAG"
                ))
        ));

        assertTrue(response.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertEquals("KnowledgeQaAgent", data.get("agent"));
        assertEquals(true, data.get("delegated"));
        assertEquals("STREAM_EXECUTION", data.get("delegationType"));
        assertEquals("KNOWLEDGE_QA", data.get("taskType"));
        assertEquals("给我介绍一下Redis", data.get("question"));
        assertEquals("RAG", data.get("retrievalModeRequested"));
        verify(handler, never()).handle(any());
    }

    @Test
    void shouldCarryDomainOnlyPrefilterSlotsIntoResolvedTaskDispatch() {
        IntentPreFilter intentPreFilter = mock(IntentPreFilter.class);
        IntentTreeRoutingService intentTreeRoutingService = mock(IntentTreeRoutingService.class);
        when(intentPreFilter.filter("我想练两道Java选择题")).thenReturn(Optional.of(
                PreFilterResult.domainOnly("CODING", Map.of(
                        "topic", "Java",
                        "questionType", "CHOICE",
                        "count", 2
                ))
        ));
        when(intentTreeRoutingService.enabled()).thenReturn(true);
        when(intentTreeRoutingService.route("我想练两道Java选择题", "history", "CODING"))
                .thenReturn(new IntentRoutingDecision(
                        false,
                        "CODING_PRACTICE",
                        0.91,
                        "domain-hit",
                        Map.of(),
                        List.of(),
                        false,
                        "",
                        List.of()
                ));
        when(intentTreeRoutingService.refineSlots("CODING_PRACTICE", "我想练两道Java选择题", "history"))
                .thenReturn(Map.of());

        TaskHandler handler = new TaskHandler() {
            @Override
            public TaskType taskType() {
                return TaskType.CODING_PRACTICE;
            }

            @Override
            public String receiverName() {
                return "CodingPracticeAgent";
            }

            @Override
            public Object handle(TaskRequest request) {
                return request.payload();
            }
        };

        TaskRouterAgent taskRouterAgent = new TaskRouterAgent(
                mock(InterviewOrchestratorAgent.class),
                mock(CodingPracticeAgent.class),
                mock(NoteMakingAgent.class),
                mock(LearningProfileAgent.class),
                mock(KnowledgeQaAgent.class),
                mock(PromptManager.class),
                intentTreeRoutingService,
                intentPreFilter,
                mock(A2ABus.class),
                mock(RoutingChatService.class),
                mock(RAGObservabilityService.class),
                new TaskHandlerRegistry(List.of(handler))
        );

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                null,
                Map.of("query", "我想练两道Java选择题"),
                new java.util.HashMap<>(Map.of("traceId", "trace-domain-slots", "history", "history"))
        ));

        assertTrue(response.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.data();
        assertEquals("Java", data.get("topic"));
        assertEquals("CHOICE", data.get("questionType"));
        assertEquals(2, data.get("count"));
        assertEquals("选择题", data.get("type"));
        verify(intentTreeRoutingService).route("我想练两道Java选择题", "history", "CODING");
    }
}
