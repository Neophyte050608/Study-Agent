package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIntent;
import com.example.interview.agent.a2a.A2AMessage;
import com.example.interview.agent.a2a.A2AMetadata;
import com.example.interview.agent.a2a.A2AStatus;
import com.example.interview.agent.a2a.A2ATrace;
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
import com.example.interview.core.RAGTraceContext;
import com.example.interview.service.IntentTreeRoutingService;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.PromptManager;
import com.example.interview.service.RAGObservabilityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务路由智能体 (TaskRouterAgent)
 * 
 * 核心职责：作为整个智能体系统的“总调度员”和“消息网关”。
 * 1. 路由分发：将外部输入的 TaskRequest 根据其类型 (TaskType) 路由到具体的业务 Agent。
 * 2. 状态观测：通过 A2A 总线实时发布任务的处理进度。
 * 3. ReAct 模式：支持基于大模型推理的自动意图识别与分发。
 */
@Component
public class TaskRouterAgent {

    private final InterviewOrchestratorAgent interviewOrchestratorAgent;
    private final CodingPracticeAgent codingPracticeAgent;
    private final NoteMakingAgent noteMakingAgent;
    private final LearningProfileAgent learningProfileAgent;
    private final KnowledgeQaAgent knowledgeQaAgent;
    private final PromptManager promptManager;
    private final IntentTreeRoutingService intentTreeRoutingService;
    private final IntentPreFilter intentPreFilter;
    private final A2ABus a2aBus;
    private final RoutingChatService routingChatService;
    private final RAGObservabilityService ragObservabilityService;
    private final TaskHandlerRegistry taskHandlerRegistry;

    @Autowired
    public TaskRouterAgent(
            InterviewOrchestratorAgent interviewOrchestratorAgent,
            CodingPracticeAgent codingPracticeAgent,
            NoteMakingAgent noteMakingAgent,
            LearningProfileAgent learningProfileAgent,
            KnowledgeQaAgent knowledgeQaAgent,
            PromptManager promptManager,
            IntentTreeRoutingService intentTreeRoutingService,
            IntentPreFilter intentPreFilter,
            A2ABus a2aBus,
            RoutingChatService routingChatService,
            RAGObservabilityService ragObservabilityService,
            TaskHandlerRegistry taskHandlerRegistry
    ) {
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
        this.codingPracticeAgent = codingPracticeAgent;
        this.noteMakingAgent = noteMakingAgent;
        this.learningProfileAgent = learningProfileAgent;
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.promptManager = promptManager;
        this.intentTreeRoutingService = intentTreeRoutingService;
        this.intentPreFilter = intentPreFilter;
        this.a2aBus = a2aBus;
        this.routingChatService = routingChatService;
        this.ragObservabilityService = ragObservabilityService;
        this.taskHandlerRegistry = taskHandlerRegistry;
    }

    /**
     * 统一调度入口：负责接收任务请求并分发给对应的具体业务 Agent。
     * 
     * @param request 任务请求对象，包含任务类型、负载数据和上下文信息。
     * @return TaskResponse 任务执行的响应结果。
     */
    public TaskResponse dispatch(TaskRequest request) {
        if (request == null) {
            return TaskResponse.fail("请求不能为空");
        }

        // 0. ReAct 模式增强：如果未指定 taskType（如来自 IM 渠道的自然语言输入），则使用大模型进行意图推理和分类
        if (request.taskType() == null) {
            return treeIntentDispatch(request);
        }
        
        // 1. 解析分布式追踪与消息关联相关的上下文 ID
        String correlationId = resolveCorrelationId(request.context());
        String traceId = resolveTraceId(request.context());
        String parentMessageId = resolveParentMessageId(request.context());
        String replyTo = resolveReplyTo(request.context());
        
        // 2. 初始化 RAG 观测上下文，用于记录整个调度过程的耗时与状态
        RAGTraceContext.setTraceId(traceId);
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "ROOT", "Task Dispatch: " + request.taskType());
        
        TaskHandler handler = null;
        TaskResponse response;
        try {
            if (shouldDelegateKnowledgeQaStream(request)) {
                response = TaskResponse.ok(buildKnowledgeQaStreamDelegation(request));
                publishReply(response, request.taskType(), replyTo, correlationId, traceId);
                ragObservabilityService.endNode(traceId, nodeId, request.payload().toString(), "delegated", null);
                return response;
            }

            handler = taskHandlerRegistry.require(request.taskType());

            // 3. 发布任务状态：PENDING（通知 A2A 总线任务已接收）
            publish(request, handler.receiverName(), A2AStatus.PENDING, correlationId, traceId, parentMessageId);

            // 4. 发布任务状态：PROCESSING（通知 A2A 总线任务开始处理）
            publish(request, handler.receiverName(), A2AStatus.PROCESSING, correlationId, traceId, parentMessageId);

            response = TaskResponse.ok(handler.handle(request));
            
            // 6. 发布任务状态：DONE/FAILED（根据响应结果通知 A2A 总线任务结束）
            publish(request, handler.receiverName(), response.success() ? A2AStatus.DONE : A2AStatus.FAILED, correlationId, traceId, parentMessageId);
            
            // 7. 处理异步回传请求（如果请求方要求异步通知结果，例如 Webhook 模式）
            publishReply(response, request.taskType(), replyTo, correlationId, traceId);
            
            // 8. 结束 RAG 观测节点，记录正常结束状态
            ragObservabilityService.endNode(traceId, nodeId, request.payload().toString(), response.message(), null);
            return response;
        } catch (Exception e) {
            // 异常兜底：发布失败状态、回传失败消息，并记录 RAG 观测节点的错误信息
            if (handler != null) {
                publish(request, handler.receiverName(), A2AStatus.FAILED, correlationId, traceId, parentMessageId);
            }
            publishReply(TaskResponse.fail(e.getMessage()), request.taskType(), replyTo, correlationId, traceId);
            ragObservabilityService.endNode(traceId, nodeId, request.payload().toString(), null, e.getMessage());
            return TaskResponse.fail("任务路由失败: " + e.getMessage());
        } finally {
            // 清理当前线程的 Trace 上下文，防止内存泄漏或污染
            RAGTraceContext.clear();
        }
    }

    /**
     * ReAct 推理模式分发
     */
    private TaskResponse treeIntentDispatch(TaskRequest request) {
        String naturalLanguageQuery = readText(request.payload(), "query");
        String history = readText(request.context(), "history");
        if (naturalLanguageQuery.isBlank()) {
            return TaskResponse.fail("未指定 taskType 且 query 为空，无法进行意图识别");
        }

        Optional<PreFilterResult> preFilterOpt = intentPreFilter == null
                ? Optional.empty()
                : intentPreFilter.filter(naturalLanguageQuery);
        if (preFilterOpt.isPresent()) {
            PreFilterResult preFilterResult = preFilterOpt.get();
            markRouteSource(request.context(), "pre-filter");

            if ("CLEAR_CONTEXT".equals(preFilterResult.taskType())) {
                if (request.context() != null) {
                    try {
                        request.context().put("clearSession", true);
                    } catch (UnsupportedOperationException ignored) {
                    }
                }
                return immediateSuccessResponse(
                        request,
                        "PRE_FILTER_REPLY",
                        Map.of("question", preFilterResult.directReply(), "clearSession", true)
                );
            }

            if (preFilterResult.directReply() != null && preFilterResult.taskType() == null) {
                return immediateSuccessResponse(
                        request,
                        "PRE_FILTER_REPLY",
                        Map.of("question", preFilterResult.directReply())
                );
            }

            try {
                TaskType taskType = TaskType.valueOf(preFilterResult.taskType().toUpperCase());
                Map<String, Object> newPayload = new LinkedHashMap<>(request.payload() == null ? Map.of() : request.payload());
                if (preFilterResult.slots() != null && !preFilterResult.slots().isEmpty()) {
                    newPayload.putAll(preFilterResult.slots());
                }
                fillMissingSlotsFromQuery(naturalLanguageQuery, newPayload);
                if (requiresIntentTreeClarification(taskType, newPayload)) {
                    markRouteSource(request.context(), "intent-tree");
                    return routeByIntentTree(request, naturalLanguageQuery, history);
                }
                normalizeQuestionType(newPayload);

                TaskRequest newRequest = new TaskRequest(taskType, newPayload, request.context());
                return dispatch(newRequest);
            } catch (IllegalArgumentException ignored) {
                // taskType 不合法时，静默降级到 Layer 2。
            }
        }

        markRouteSource(request.context(), "intent-tree");
        return routeByIntentTree(request, naturalLanguageQuery, history);
    }

    private TaskResponse routeByIntentTree(TaskRequest request, String naturalLanguageQuery, String history) {
        if (intentTreeRoutingService == null || !intentTreeRoutingService.enabled()) {
            return reactDispatch(request);
        }
        IntentRoutingDecision decision = intentTreeRoutingService.route(naturalLanguageQuery, history);
        if (decision.fallbackToLegacy()) {
            return reactDispatch(request);
        }
        if (decision.askClarification()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("question", decision.clarificationQuestion());
            data.put("clarification", true);
            data.put("clarificationOptions", decision.clarificationOptions());
            data.put("originalQuery", naturalLanguageQuery);
            data.put("confidence", decision.confidence());
            data.put("reason", decision.reason());
            return TaskResponse.ok(data);
        }
        if ("UNKNOWN".equalsIgnoreCase(decision.taskType())) {
            return TaskResponse.ok(Map.of(
                    "question",
                    "我没完全理解你的意图。你可以说“开始模拟面试”、“来一道算法题”或“查询学习计划”。"
            ));
        }
        TaskType taskType;
        try {
            taskType = TaskType.valueOf(decision.taskType().toUpperCase());
        } catch (Exception ex) {
            return reactDispatch(request);
        }
        Map<String, Object> newPayload = new LinkedHashMap<>(request.payload() == null ? Map.of() : request.payload());
        if (decision.slots() != null && !decision.slots().isEmpty()) {
            newPayload.putAll(decision.slots());
        }

        // 槽位补全增强：如果识别到了具体任务类型，但关键槽位可能缺失，尝试二次精炼
        if (!"UNKNOWN".equalsIgnoreCase(decision.taskType()) && !decision.askClarification()) {
            Map<String, Object> refinedSlots = intentTreeRoutingService.refineSlots(decision.taskType(), naturalLanguageQuery, history);
            if (refinedSlots != null && !refinedSlots.isEmpty()) {
                refinedSlots.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isBlank()) {
                        newPayload.put(k, v);
                    }
                });
            }
        }

        fillMissingSlotsFromQuery(naturalLanguageQuery, newPayload);
        normalizeQuestionType(newPayload);

        // 兜底：如果 LLM 未提取 type/questionType，从原始 query 中推断题型
        if (!newPayload.containsKey("type") || String.valueOf(newPayload.get("type")).isBlank()) {
            if (naturalLanguageQuery.contains("选择") || naturalLanguageQuery.contains("单选") || naturalLanguageQuery.contains("多选")) {
                newPayload.put("type", "选择题");
            }
        }

        TaskRequest newRequest = new TaskRequest(taskType, newPayload, request.context());
        return dispatch(newRequest);
    }

    private boolean requiresIntentTreeClarification(TaskType taskType, Map<String, Object> payload) {
        return taskType == TaskType.CODING_PRACTICE
                && readText(payload, "topic").isBlank()
                && readText(payload, "questionType").isBlank();
    }

    /**
     * ReAct 推理模式分发
     */
    private TaskResponse reactDispatch(TaskRequest request) {
        markRouteSource(request.context(), "react-router");
        String naturalLanguageQuery = readText(request.payload(), "query");
        String history = readText(request.context(), "history");
        if (naturalLanguageQuery.isBlank()) {
            return TaskResponse.fail("未指定 taskType 且 query 为空，无法进行 ReAct 推理");
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("query", naturalLanguageQuery);
        vars.put("history", history);
        String prompt = promptManager.render("task-router", vars);

        try {
            String reactDecisionStr = routingChatService.callWithFirstPacketProbeSupplier(
                () -> "{\"taskType\":\"UNKNOWN\"}",
                prompt, 
                ModelRouteType.THINKING, 
                TimeoutHint.FAST,
                "任务路由ReAct"
            );

            // 简单解析决策结果
            String decidedTaskType = "";
            String topic = "";
            String questionType = "";
            if (reactDecisionStr != null) {
                if (reactDecisionStr.contains("INTERVIEW_START")) decidedTaskType = "INTERVIEW_START";
                else if (reactDecisionStr.contains("INTERVIEW_ANSWER")) decidedTaskType = "INTERVIEW_ANSWER";
                else if (reactDecisionStr.contains("INTERVIEW_REPORT")) decidedTaskType = "INTERVIEW_REPORT";
                else if (reactDecisionStr.contains("CODING_PRACTICE")) decidedTaskType = "CODING_PRACTICE";
                else if (reactDecisionStr.contains("LEARNING_PLAN")) decidedTaskType = "LEARNING_PLAN";
                else if (reactDecisionStr.contains("PROFILE_EVENT_UPSERT")) decidedTaskType = "PROFILE_EVENT_UPSERT";
                else if (reactDecisionStr.contains("PROFILE_TRAINING_PLAN_QUERY")) decidedTaskType = "PROFILE_TRAINING_PLAN_QUERY";
                else if (reactDecisionStr.contains("PROFILE_SNAPSHOT_QUERY")) decidedTaskType = "PROFILE_SNAPSHOT_QUERY";
                else if (reactDecisionStr.contains("KNOWLEDGE_QA")) decidedTaskType = "KNOWLEDGE_QA";
                else if (reactDecisionStr.contains("UNKNOWN")) decidedTaskType = "UNKNOWN";

                java.util.regex.Matcher mTopic = java.util.regex.Pattern.compile("\"topic\"\\s*:\\s*\"([^\"]+)\"").matcher(reactDecisionStr);
                if (mTopic.find()) topic = mTopic.group(1);

                java.util.regex.Matcher mType = java.util.regex.Pattern.compile("\"questionType\"\\s*:\\s*\"([^\"]+)\"").matcher(reactDecisionStr);
                if (mType.find()) questionType = mType.group(1);

                if (!decidedTaskType.isBlank()) {
                    // 处理兜底策略
                    if ("UNKNOWN".equals(decidedTaskType)) {
                        String fallbackMessage = "抱歉，我没有理解你的意图。我是你的 AI 面试官助理，你可以尝试以下功能：\n" +
                                "1. 模拟面试：例如“开启一场 Spring Boot 面试”\n" +
                                "2. 题目练习：例如“来一道 Java 选择题”或“刷一道算法题”\n" +
                                "3. 学习画像：例如“查询我的学习计划”\n" +
                                "请告诉我你想进行哪项操作。";
                        // 返回通用 Map 结构，ImWebhookService 会提取并展示内容
                        return TaskResponse.ok(Map.of("question", fallbackMessage));
                    }

                    Map<String, Object> newPayload = new LinkedHashMap<>(request.payload() == null ? Map.of() : request.payload());
                    if (!topic.isBlank()) newPayload.put("topic", topic);
                    if (!questionType.isBlank()) {
                        newPayload.put("questionType", questionType);
                    }
                    normalizeQuestionType(newPayload);

                    // 检查是否主动跳过自我介绍
                    if (naturalLanguageQuery.contains("跳过自我介绍") || naturalLanguageQuery.contains("直接出题") || naturalLanguageQuery.contains("跳过介绍")) {
                        newPayload.put("skipIntro", true);
                    }

                    TaskRequest newRequest = new TaskRequest(TaskType.valueOf(decidedTaskType), newPayload, request.context());
                    return dispatch(newRequest);
                } else {
                    return TaskResponse.fail("ReAct 思考失败，无法识别意图");
                }
            }
            return TaskResponse.fail("ReAct 思考失败，模型返回结果为空");
        } catch (Exception e) {
            return TaskResponse.fail("ReAct 推理异常: " + e.getMessage());
        }
    }

    // --- 辅助工具方法 ---

    private String readText(Map<String, Object> payload, String key) {
        if (payload == null) return "";
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private void normalizeQuestionType(Map<String, Object> payload) {
        String questionType = readText(payload, "questionType");
        if (questionType.isBlank()) {
            return;
        }
        String qt = questionType.toUpperCase();
        String typeName;
        if ("CHOICE".equals(qt) || qt.contains("选择") || qt.contains("单选") || qt.contains("多选")) {
            typeName = "选择题";
        } else if ("FILL".equals(qt) || qt.contains("填空") || qt.contains("补全")) {
            typeName = "填空题";
        } else if ("SCENARIO".equals(qt) || qt.contains("场景")) {
            typeName = "场景题";
        } else {
            typeName = "算法题";
        }
        payload.put("type", typeName);
    }

    private void fillMissingSlotsFromQuery(String naturalLanguageQuery, Map<String, Object> payload) {
        if (intentPreFilter != null) {
            intentPreFilter.fillMissingSlots(naturalLanguageQuery, payload);
        }
    }

    private boolean shouldDelegateKnowledgeQaStream(TaskRequest request) {
        if (request == null || request.taskType() != TaskType.KNOWLEDGE_QA) {
            return false;
        }
        String executionMode = readText(request.context(), "executionMode");
        return ExecutionMode.STREAM_ROUTE_ONLY.name().equalsIgnoreCase(executionMode);
    }

    private Map<String, Object> buildKnowledgeQaStreamDelegation(TaskRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "KnowledgeQaAgent");
        data.put("delegated", true);
        data.put("delegationType", "STREAM_EXECUTION");
        data.put("taskType", TaskType.KNOWLEDGE_QA.name());
        data.put("question", readText(request.payload(), "query"));
        String retrievalMode = readText(request.context(), "retrievalMode");
        if (!retrievalMode.isBlank()) {
            data.put("retrievalModeRequested", retrievalMode);
        }
        return data;
    }

    private void publish(TaskRequest request, String receiver, A2AStatus status, String correlationId, String traceId, String parentMessageId) {
        String messageId = UUID.randomUUID().toString();
        a2aBus.publish(new A2AMessage(
                "1.0", messageId, correlationId, "TaskRouterAgent", receiver, "", A2AIntent.DELEGATE_TASK,
                Map.of("taskType", request.taskType().name(), "status", status.name()),
                request.context() == null ? Map.of() : request.context(), status, null,
                new A2AMetadata("task-routing", "TaskRouterAgent", Map.of("taskType", request.taskType().name())),
                new A2ATrace(traceId, parentMessageId), Instant.now()
        ));
    }

    private String resolveCorrelationId(Map<String, Object> context) {
        String fromContext = readText(context, "correlationId");
        return fromContext.isBlank() ? UUID.randomUUID().toString() : fromContext;
    }

    private String resolveTraceId(Map<String, Object> context) {
        String fromContext = readText(context, "traceId");
        return fromContext.isBlank() ? UUID.randomUUID().toString() : fromContext;
    }

    private String resolveParentMessageId(Map<String, Object> context) {
        return readText(context, "parentMessageId");
    }

    private String resolveReplyTo(Map<String, Object> context) {
        return readText(context, "replyTo");
    }

    private void publishReply(TaskResponse response, TaskType taskType, String replyTo, String correlationId, String traceId) {
        publishReply(response, taskType == null ? "" : taskType.name(), replyTo, correlationId, traceId);
    }

    private void publishReply(TaskResponse response, String taskTypeName, String replyTo, String correlationId, String traceId) {
        if (replyTo == null || replyTo.isBlank()) return;
        String messageId = UUID.randomUUID().toString();
        a2aBus.publish(new A2AMessage(
                "1.0", messageId, correlationId, "TaskRouterAgent", replyTo, "", A2AIntent.RETURN_RESULT,
                Map.of("taskType", taskTypeName, "success", response.success(), "message", response.message() == null ? "" : response.message(), "data", response.data() == null ? Map.of() : response.data()),
                Map.of(), response.success() ? A2AStatus.DONE : A2AStatus.FAILED,
                response.success() ? null : new com.example.interview.agent.a2a.A2AError("TASK_FAILED", response.message()),
                new A2AMetadata("task-routing-reply", "TaskRouterAgent", Map.of("taskType", taskTypeName)),
                new A2ATrace(traceId, messageId), Instant.now()
        ));
    }

    private TaskResponse immediateSuccessResponse(TaskRequest request, String taskTypeName, Object data) {
        String correlationId = resolveCorrelationId(request.context());
        String traceId = resolveTraceId(request.context());
        String replyTo = resolveReplyTo(request.context());
        String nodeId = UUID.randomUUID().toString();
        RAGTraceContext.setTraceId(traceId);
        TaskResponse response = TaskResponse.ok(data);
        try {
            ragObservabilityService.startNode(traceId, nodeId, null, "ROOT", "Task Dispatch: " + taskTypeName);
            publishReply(response, taskTypeName, replyTo, correlationId, traceId);
            ragObservabilityService.endNode(traceId, nodeId, request.payload() == null ? "{}" : request.payload().toString(), response.message(), null);
            return response;
        } finally {
            RAGTraceContext.clear();
        }
    }

    private void markRouteSource(Map<String, Object> context, String routeSource) {
        if (context == null || routeSource == null || routeSource.isBlank()) {
            return;
        }
        try {
            context.put("routeSource", routeSource);
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
