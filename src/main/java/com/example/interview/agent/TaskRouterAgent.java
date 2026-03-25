package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIntent;
import com.example.interview.agent.a2a.A2AMessage;
import com.example.interview.agent.a2a.A2AMetadata;
import com.example.interview.agent.a2a.A2AStatus;
import com.example.interview.agent.a2a.A2ATrace;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.IntentTreeRoutingService;
import com.example.interview.service.LearningEvent;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.LearningSource;
import com.example.interview.service.PromptManager;
import com.example.interview.service.TrainingProfileSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.interview.service.RAGObservabilityService;
import com.example.interview.core.RAGTraceContext;

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
    private final PromptManager promptManager;
    private final IntentTreeRoutingService intentTreeRoutingService;
    private final A2ABus a2aBus;
    private final RoutingChatService routingChatService;
    private final RAGObservabilityService ragObservabilityService;

    @Autowired
    public TaskRouterAgent(
            InterviewOrchestratorAgent interviewOrchestratorAgent,
            CodingPracticeAgent codingPracticeAgent,
            NoteMakingAgent noteMakingAgent,
            LearningProfileAgent learningProfileAgent,
            PromptManager promptManager,
            IntentTreeRoutingService intentTreeRoutingService,
            A2ABus a2aBus,
            RoutingChatService routingChatService,
            RAGObservabilityService ragObservabilityService
    ) {
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
        this.codingPracticeAgent = codingPracticeAgent;
        this.noteMakingAgent = noteMakingAgent;
        this.learningProfileAgent = learningProfileAgent;
        this.promptManager = promptManager;
        this.intentTreeRoutingService = intentTreeRoutingService;
        this.a2aBus = a2aBus;
        this.routingChatService = routingChatService;
        this.ragObservabilityService = ragObservabilityService;
    }

    /**
     * 统一调度入口
     */
    public TaskResponse dispatch(TaskRequest request) {
        if (request == null) {
            return TaskResponse.fail("请求不能为空");
        }

        // 0. ReAct 模式增强：如果未指定 taskType，使用大模型自主推理
        if (request.taskType() == null) {
            return treeIntentDispatch(request);
        }
        
        String correlationId = resolveCorrelationId(request.context());
        String traceId = resolveTraceId(request.context());
        String parentMessageId = resolveParentMessageId(request.context());
        String replyTo = resolveReplyTo(request.context());
        
        // 统一 Trace 上下文
        RAGTraceContext.setTraceId(traceId);
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "ROOT", "Task Dispatch: " + request.taskType());
        
        // 发布任务状态：PENDING
        publish(request, receiverOf(request.taskType()), A2AStatus.PENDING, correlationId, traceId, parentMessageId);
        
        TaskResponse response;
        try {
            // 发布任务状态：PROCESSING
            publish(request, receiverOf(request.taskType()), A2AStatus.PROCESSING, correlationId, traceId, parentMessageId);
            
            // 使用 Java 21+ 的现代 Switch 表达式进行路由分发
            response = switch (request.taskType()) {
                case INTERVIEW_START -> TaskResponse.ok(routeInterviewStart(request.payload()));
                case INTERVIEW_ANSWER -> TaskResponse.ok(routeInterviewAnswer(request.payload()));
                case INTERVIEW_REPORT -> TaskResponse.ok(routeInterviewReport(request.payload()));
                case LEARNING_PLAN -> TaskResponse.ok(noteMakingAgent.execute(safePayload(request.payload())));
                case CODING_PRACTICE -> TaskResponse.ok(codingPracticeAgent.execute(enrichCodingPayload(request.payload(), request.context())));
                case PROFILE_EVENT_UPSERT -> TaskResponse.ok(routeProfileEventUpsert(request.payload(), request.context()));
                case PROFILE_SNAPSHOT_QUERY -> TaskResponse.ok(routeProfileSnapshot(request.payload(), request.context()));
                case PROFILE_TRAINING_PLAN_QUERY -> TaskResponse.ok(routeProfileTrainingPlan(request.payload(), request.context()));
            };
            
            // 发布任务状态：DONE/FAILED
            publish(request, receiverOf(request.taskType()), response.success() ? A2AStatus.DONE : A2AStatus.FAILED, correlationId, traceId, parentMessageId);
            
            // 处理异步回传请求
            publishReply(response, request.taskType(), replyTo, correlationId, traceId);
            
            ragObservabilityService.endNode(traceId, nodeId, request.payload().toString(), response.message(), null);
            return response;
        } catch (Exception e) {
            publish(request, receiverOf(request.taskType()), A2AStatus.FAILED, correlationId, traceId, parentMessageId);
            publishReply(TaskResponse.fail(e.getMessage()), request.taskType(), replyTo, correlationId, traceId);
            ragObservabilityService.endNode(traceId, nodeId, request.payload().toString(), null, e.getMessage());
            return TaskResponse.fail("任务路由失败: " + e.getMessage());
        } finally {
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
        if (intentTreeRoutingService == null || !intentTreeRoutingService.enabled()) {
            return reactDispatch(request);
        }
        com.example.interview.intent.IntentRoutingDecision decision = intentTreeRoutingService.route(naturalLanguageQuery, history);
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
        Map<String, Object> newPayload = new LinkedHashMap<>(safePayload(request.payload()));
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

        normalizeQuestionType(newPayload);
        if (naturalLanguageQuery.contains("跳过自我介绍") || naturalLanguageQuery.contains("直接出题") || naturalLanguageQuery.contains("跳过介绍")) {
            newPayload.put("skipIntro", true);
        }
        TaskRequest newRequest = new TaskRequest(taskType, newPayload, request.context());
        return dispatch(newRequest);
    }

    /**
     * ReAct 推理模式分发
     */
    private TaskResponse reactDispatch(TaskRequest request) {
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
                "任务路由ReAct"
            );

            // 简单解析决策结果
            String decidedTaskType = "";
            String topic = "";
            String questionType = "";
            if (reactDecisionStr != null) {
                if (reactDecisionStr.contains("INTERVIEW_START")) decidedTaskType = "INTERVIEW_START";
                else if (reactDecisionStr.contains("CODING_PRACTICE")) decidedTaskType = "CODING_PRACTICE";
                else if (reactDecisionStr.contains("PROFILE_TRAINING_PLAN_QUERY")) decidedTaskType = "PROFILE_TRAINING_PLAN_QUERY";
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

                    Map<String, Object> newPayload = new LinkedHashMap<>(safePayload(request.payload()));
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

    // --- 路由处理子方法 ---

    private InterviewSession routeInterviewStart(Map<String, Object> payload) {
        boolean skipIntro = false;
        if (payload != null && payload.containsKey("skipIntro")) {
            Object val = payload.get("skipIntro");
            if (val instanceof Boolean b) skipIntro = b;
            else if (val instanceof String s) skipIntro = Boolean.parseBoolean(s);
        }
        return interviewOrchestratorAgent.startSession(
            readText(payload, "userId"), 
            readText(payload, "topic"), 
            readText(payload, "resumePath"), 
            readInt(payload, "totalQuestions"),
            skipIntro
        );
    }

    private InterviewOrchestratorAgent.AnswerResult routeInterviewAnswer(Map<String, Object> payload) {
        return interviewOrchestratorAgent.submitAnswer(readText(payload, "sessionId"), readText(payload, "userAnswer"));
    }

    private InterviewOrchestratorAgent.FinalReport routeInterviewReport(Map<String, Object> payload) {
        return interviewOrchestratorAgent.generateFinalReport(readText(payload, "sessionId"), readText(payload, "userId"));
    }

    private Map<String, Object> routeProfileEventUpsert(Map<String, Object> payload, Map<String, Object> context) {
        LearningEvent event = new LearningEvent(
                readText(payload, "eventId"),
                resolveUserId(payload, context),
                parseSource(readText(payload, "source")),
                readText(payload, "topic"),
                readInt(payload, "score") == null ? 60 : readInt(payload, "score"),
                readTextList(payload, "weakPoints"),
                readTextList(payload, "familiarPoints"),
                readText(payload, "evidence"),
                parseTimestamp(readText(payload, "timestamp"))
        );
        boolean inserted = learningProfileAgent.upsertEvent(event);
        return Map.of("agent", "LearningProfileAgent", "status", inserted ? "inserted" : "duplicated", "eventId", event.eventId());
    }

    private TrainingProfileSnapshot routeProfileSnapshot(Map<String, Object> payload, Map<String, Object> context) {
        return learningProfileAgent.snapshot(resolveUserId(payload, context));
    }

    private Map<String, Object> routeProfileTrainingPlan(Map<String, Object> payload, Map<String, Object> context) {
        String mode = readText(payload, "mode");
        String userId = resolveUserId(payload, context);
        return Map.of("agent", "LearningProfileAgent", "mode", mode.isBlank() ? "interview" : mode.toLowerCase(), "recommendation", learningProfileAgent.recommend(userId, mode));
    }

    // --- 辅助工具方法 ---

    private String readText(Map<String, Object> payload, String key) {
        if (payload == null) return "";
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer readInt(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object raw = payload.get(key);
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String text && !text.isBlank()) {
            try { return Integer.parseInt(text.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }

    private Map<String, Object> enrichCodingPayload(Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> merged = new LinkedHashMap<>(safePayload(payload));
        if (!merged.containsKey("userId")) {
            merged.put("userId", resolveUserId(payload, context));
        }
        // 确保从 payload 中透传 type (选择题/填空题/算法题)
        if (payload != null && payload.containsKey("type")) {
            merged.put("type", payload.get("type"));
        }
        return merged;
    }

    private void normalizeQuestionType(Map<String, Object> payload) {
        String questionType = readText(payload, "questionType");
        if (questionType.isBlank()) {
            return;
        }
        String typeName = switch (questionType.toUpperCase()) {
            case "CHOICE" -> "选择题";
            case "FILL" -> "填空题";
            default -> "算法题";
        };
        payload.put("type", typeName);
    }

    private String receiverOf(TaskType taskType) {
        return switch (taskType) {
            case INTERVIEW_START, INTERVIEW_ANSWER, INTERVIEW_REPORT -> "InterviewOrchestratorAgent";
            case LEARNING_PLAN -> "NoteAgent";
            case CODING_PRACTICE -> "CodingAgent";
            case PROFILE_EVENT_UPSERT, PROFILE_SNAPSHOT_QUERY, PROFILE_TRAINING_PLAN_QUERY -> "LearningProfileAgent";
        };
    }

    private String resolveUserId(Map<String, Object> payload, Map<String, Object> context) {
        String fromPayload = readText(payload, "userId");
        if (!fromPayload.isBlank()) return learningProfileAgent.normalizeUserId(fromPayload);
        return learningProfileAgent.normalizeUserId(readText(context, "userId"));
    }

    private List<String> readTextList(Map<String, Object> payload, String key) {
        if (payload == null) return List.of();
        Object raw = payload.get(key);
        if (raw instanceof List<?> list) {
            List<String> data = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) data.add(item.toString().trim());
            }
            return data;
        }
        return List.of();
    }

    private LearningSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) return LearningSource.INTERVIEW;
        try { return LearningSource.valueOf(raw.trim().toUpperCase()); } catch (IllegalArgumentException ignored) { return LearningSource.INTERVIEW; }
    }

    private Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return Instant.now();
        try { return Instant.parse(raw.trim()); } catch (DateTimeParseException ignored) { return Instant.now(); }
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
        if (replyTo == null || replyTo.isBlank()) return;
        String messageId = UUID.randomUUID().toString();
        a2aBus.publish(new A2AMessage(
                "1.0", messageId, correlationId, "TaskRouterAgent", replyTo, "", A2AIntent.RETURN_RESULT,
                Map.of("taskType", taskType.name(), "success", response.success(), "message", response.message() == null ? "" : response.message(), "data", response.data() == null ? Map.of() : response.data()),
                Map.of(), response.success() ? A2AStatus.DONE : A2AStatus.FAILED,
                response.success() ? null : new com.example.interview.agent.a2a.A2AError("TASK_FAILED", response.message()),
                new A2AMetadata("task-routing-reply", "TaskRouterAgent", Map.of("taskType", taskType.name())),
                new A2ATrace(traceId, messageId), Instant.now()
        ));
    }
}
