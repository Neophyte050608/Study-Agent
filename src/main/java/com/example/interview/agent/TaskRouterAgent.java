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
import com.example.interview.service.LearningEvent;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.LearningSource;
import com.example.interview.service.TrainingProfileSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务路由智能体（TaskRouterAgent）。
 * 
 * 核心职责：作为整个智能体系统的“总调度员”和“消息网关”。
 * 1. 路由分发：将外部输入的 TaskRequest 根据其类型（TaskType）路由到具体的业务 Agent。
 * 2. 状态观测：通过 A2A 总线实时发布任务的处理进度（PENDING -> PROCESSING -> DONE/FAILED）。
 * 3. 结果回传：支持异步回包机制（replyTo），完成任务后自动向指定订阅方发送结果。
 * 4. 链路追踪：维护并透传 correlationId 和 traceId，确保跨 Agent 的请求能够被完整追踪。
 */
@Component
public class TaskRouterAgent {

    private final InterviewOrchestratorAgent interviewOrchestratorAgent;
    private final CodingPracticeAgent codingPracticeAgent;
    private final NoteMakingAgent noteMakingAgent;
    private final LearningProfileAgent learningProfileAgent;
    /** Agent-to-Agent 消息总线，用于跨 Agent 通信和状态发布 */
    private final A2ABus a2aBus;
    /** Spring AI ChatClient 用于 ReAct 推理 */
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    public TaskRouterAgent(
            InterviewOrchestratorAgent interviewOrchestratorAgent,
            CodingPracticeAgent codingPracticeAgent,
            NoteMakingAgent noteMakingAgent,
            LearningProfileAgent learningProfileAgent,
            A2ABus a2aBus,
            @org.springframework.beans.factory.annotation.Qualifier("openAiChatModel") org.springframework.ai.chat.model.ChatModel chatModel
    ) {
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
        this.codingPracticeAgent = codingPracticeAgent;
        this.noteMakingAgent = noteMakingAgent;
        this.learningProfileAgent = learningProfileAgent;
        this.a2aBus = a2aBus;
        this.chatClient = org.springframework.ai.chat.client.ChatClient.builder(chatModel).build();
    }

    /**
     * 统一调度入口：完成路由分发、状态发布、异常兜底与异步结果回传。
     *
     * @param request 封装了任务类型、业务数据和上下文的请求对象
     * @return 业务 Agent 执行后的即时响应
     */
    public TaskResponse dispatch(TaskRequest request) {
        if (request == null) {
            return TaskResponse.fail("请求不能为空");
        }

        // 0. ReAct 模式增强：如果未指定 taskType，使用大模型自主推理和行动（路由）
        if (request.taskType() == null) {
            return reactDispatch(request);
        }
        
        // 1. 解析上下文中的追踪标识
        String correlationId = resolveCorrelationId(request.context());
        String traceId = resolveTraceId(request.context());
        String parentMessageId = resolveParentMessageId(request.context());
        String replyTo = resolveReplyTo(request.context());
        
        // 2. 发布任务已挂起的观测消息
        publish(request, receiverOf(request.taskType()), A2AStatus.PENDING, correlationId, traceId, parentMessageId);
        
        try {
            // 3. 发布任务开始处理的观测消息
            publish(request, receiverOf(request.taskType()), A2AStatus.PROCESSING, correlationId, traceId, parentMessageId);
            
            // 4. 根据任务类型分发到对应的业务 Agent
            TaskResponse response = switch (request.taskType()) {
                case INTERVIEW_START -> TaskResponse.ok(routeInterviewStart(request.payload()));
                case INTERVIEW_ANSWER -> TaskResponse.ok(routeInterviewAnswer(request.payload()));
                case INTERVIEW_REPORT -> TaskResponse.ok(routeInterviewReport(request.payload()));
                case LEARNING_PLAN -> TaskResponse.ok(noteMakingAgent.execute(safePayload(request.payload())));
                case CODING_PRACTICE -> TaskResponse.ok(codingPracticeAgent.execute(enrichCodingPayload(request.payload(), request.context())));
                case PROFILE_EVENT_UPSERT -> TaskResponse.ok(routeProfileEventUpsert(request.payload(), request.context()));
                case PROFILE_SNAPSHOT_QUERY -> TaskResponse.ok(routeProfileSnapshot(request.payload(), request.context()));
                case PROFILE_TRAINING_PLAN_QUERY -> TaskResponse.ok(routeProfileTrainingPlan(request.payload(), request.context()));
            };
            
            // 5. 发布任务执行完毕（成功或逻辑失败）的观测消息
            publish(request, receiverOf(request.taskType()), response.success() ? A2AStatus.DONE : A2AStatus.FAILED, correlationId, traceId, parentMessageId);
            
            // 6. 如果有异步回包需求，发送回包消息
            publishReply(response, request.taskType(), replyTo, correlationId, traceId);
            
            return response;
        } catch (RuntimeException e) {
            // 7. 发生非预期异常，发布任务失败的观测消息并透传异常
            publish(request, receiverOf(request.taskType()), A2AStatus.FAILED, correlationId, traceId, parentMessageId);
            publishReply(TaskResponse.fail(e.getMessage()), request.taskType(), replyTo, correlationId, traceId);
            throw e;
        }
    }

    /**
     * ReAct 模式（Reasoning + Acting）任务分发。
     * 当外部输入一个自然语言意图而非严格的 TaskType 时，TaskRouterAgent 会使用大模型进行思考，
     * 观察用户的意图，并自主决定调用哪个具体的任务处理方法。
     */
    private TaskResponse reactDispatch(TaskRequest request) {
        String naturalLanguageQuery = readText(request.payload(), "query");
        if (naturalLanguageQuery.isBlank()) {
            return TaskResponse.fail("未指定 taskType 且 query 为空，无法进行 ReAct 推理");
        }

        System.out.println("====== [TaskRouterAgent - ReAct] 开始思考意图 ======");
        System.out.println("Observation (用户输入): " + naturalLanguageQuery);

        // 模拟 Thought 过程：要求大模型根据支持的 TaskType 输出对应的分类和提取的参数
        String prompt = "你是一个智能任务路由网关（TaskRouterAgent）。\n" +
                "你支持以下任务类型（TaskType）：\n" +
                "1. INTERVIEW_START：开启一场模拟面试，需要提取 topic(面试主题)。\n" +
                "2. CODING_PRACTICE：进行算法刷题训练，需要提取 topic(题目类型)。\n" +
                "3. PROFILE_TRAINING_PLAN_QUERY：查询学习计划，不需要额外参数。\n" +
                "请分析用户的自然语言输入，并决定你应该执行哪个任务。\n" +
                "用户输入：" + naturalLanguageQuery + "\n" +
                "请严格以JSON格式输出，包含字段：taskType, topic, reason (你的思考过程)。";

        try {
            String reactDecisionStr = chatClient.prompt().user(prompt).call().content();
            System.out.println("====== [TaskRouterAgent - ReAct] 思考与行动决策 ======");
            System.out.println(reactDecisionStr);

            // 简单解析大模型的 JSON 响应（实际项目中应使用 ObjectMapper 解析）
            String decidedTaskType = "";
            String topic = "";
            if (reactDecisionStr != null) {
                if (reactDecisionStr.contains("INTERVIEW_START")) decidedTaskType = "INTERVIEW_START";
                else if (reactDecisionStr.contains("CODING_PRACTICE")) decidedTaskType = "CODING_PRACTICE";
                else if (reactDecisionStr.contains("PROFILE_TRAINING_PLAN_QUERY")) decidedTaskType = "PROFILE_TRAINING_PLAN_QUERY";
                
                // 简单的正则提取 topic
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"topic\"\\s*:\\s*\"([^\"]+)\"").matcher(reactDecisionStr);
                if (m.find()) {
                    topic = m.group(1);
                }
            }

            // Action 阶段：根据思考结果重新组装 Request 并分发
            if (!decidedTaskType.isBlank()) {
                System.out.println("====== [TaskRouterAgent - ReAct] 采取行动: 路由至 " + decidedTaskType + " ======");
                Map<String, Object> newPayload = new LinkedHashMap<>(safePayload(request.payload()));
                if (!topic.isBlank()) newPayload.put("topic", topic);
                
                TaskRequest newRequest = new TaskRequest(TaskType.valueOf(decidedTaskType), newPayload, request.context());
                // 递归调用原本的精确调度逻辑
                return dispatch(newRequest);
            } else {
                return TaskResponse.fail("ReAct 思考失败，无法识别意图");
            }
        } catch (Exception e) {
            System.err.println("====== [TaskRouterAgent - ReAct] 推理异常: " + e.getMessage() + " ======");
            return TaskResponse.fail("ReAct 推理执行失败");
        }
    }

    /**
     * 路由：开始面试。
     */
    private InterviewSession routeInterviewStart(Map<String, Object> payload) {
        String userId = readText(payload, "userId");
        String topic = readText(payload, "topic");
        String resumePath = readText(payload, "resumePath");
        Integer totalQuestions = readInt(payload, "totalQuestions");
        return interviewOrchestratorAgent.startSession(userId, topic, resumePath, totalQuestions);
    }

    /**
     * 路由：提交面试回答。
     */
    private InterviewOrchestratorAgent.AnswerResult routeInterviewAnswer(Map<String, Object> payload) {
        String sessionId = readText(payload, "sessionId");
        String userAnswer = readText(payload, "userAnswer");
        return interviewOrchestratorAgent.submitAnswer(sessionId, userAnswer);
    }

    /**
     * 路由：生成面试总结报告。
     */
    private InterviewOrchestratorAgent.FinalReport routeInterviewReport(Map<String, Object> payload) {
        String sessionId = readText(payload, "sessionId");
        String userId = readText(payload, "userId");
        return interviewOrchestratorAgent.generateFinalReport(sessionId, userId);
    }

    /**
     * 路由：更新学习画像事件。
     */
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
        return Map.of(
                "agent", "LearningProfileAgent",
                "status", inserted ? "inserted" : "duplicated",
                "eventId", event.eventId()
        );
    }

    /**
     * 路由：查询学习画像快照。
     */
    private TrainingProfileSnapshot routeProfileSnapshot(Map<String, Object> payload, Map<String, Object> context) {
        return learningProfileAgent.snapshot(resolveUserId(payload, context));
    }

    /**
     * 路由：查询学习/练习推荐计划。
     */
    private Map<String, Object> routeProfileTrainingPlan(Map<String, Object> payload, Map<String, Object> context) {
        String mode = readText(payload, "mode");
        String userId = resolveUserId(payload, context);
        return Map.of(
                "agent", "LearningProfileAgent",
                "mode", mode.isBlank() ? "interview" : mode.toLowerCase(),
                "recommendation", learningProfileAgent.recommend(userId, mode)
        );
    }

    /**
     * 辅助工具：安全读取 payload 中的文本。
     */
    private String readText(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 辅助工具：安全读取 payload 中的整数。
     */
    private Integer readInt(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object raw = payload.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 辅助工具：确保 payload 不为空。
     */
    private Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }

    /**
     * 辅助工具：增强编程练习的参数，自动注入 userId。
     */
    private Map<String, Object> enrichCodingPayload(Map<String, Object> payload, Map<String, Object> context) {
        Map<String, Object> merged = new LinkedHashMap<>(safePayload(payload));
        if (!merged.containsKey("userId")) {
            merged.put("userId", resolveUserId(payload, context));
        }
        return merged;
    }

    /**
     * 映射：根据任务类型确定逻辑接收方的 Agent 名称。
     */
    private String receiverOf(TaskType taskType) {
        return switch (taskType) {
            case INTERVIEW_START, INTERVIEW_ANSWER, INTERVIEW_REPORT -> "InterviewOrchestratorAgent";
            case LEARNING_PLAN -> "NoteAgent";
            case CODING_PRACTICE -> "CodingAgent";
            case PROFILE_EVENT_UPSERT, PROFILE_SNAPSHOT_QUERY, PROFILE_TRAINING_PLAN_QUERY -> "LearningProfileAgent";
        };
    }

    /**
     * 辅助工具：统一解析并归一化用户ID。
     */
    private String resolveUserId(Map<String, Object> payload, Map<String, Object> context) {
        String fromPayload = readText(payload, "userId");
        if (!fromPayload.isBlank()) {
            return learningProfileAgent.normalizeUserId(fromPayload);
        }
        String fromContext = readText(context, "userId");
        return learningProfileAgent.normalizeUserId(fromContext);
    }

    /**
     * 辅助工具：读取文本列表参数。
     */
    private List<String> readTextList(Map<String, Object> payload, String key) {
        if (payload == null) {
            return List.of();
        }
        Object raw = payload.get(key);
        if (raw instanceof List<?> list) {
            List<String> data = new ArrayList<>();
            for (Object item : list) {
                String text = item == null ? "" : item.toString().trim();
                if (!text.isBlank()) {
                    data.add(text);
                }
            }
            return data;
        }
        if (raw instanceof String text && !text.isBlank()) {
            String[] split = text.split("\\R|;");
            List<String> data = new ArrayList<>();
            for (String item : split) {
                String trimmed = item.trim();
                if (!trimmed.isBlank()) {
                    data.add(trimmed);
                }
            }
            return data;
        }
        return List.of();
    }

    /**
     * 辅助工具：解析学习来源枚举。
     */
    private LearningSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return LearningSource.INTERVIEW;
        }
        try {
            return LearningSource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return LearningSource.INTERVIEW;
        }
    }

    /**
     * 辅助工具：解析时间戳。
     */
    private Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }

    /**
     * 向 A2A 总线发布观测消息。
     */
    private void publish(TaskRequest request, String receiver, A2AStatus status, String correlationId, String traceId, String parentMessageId) {
        String messageId = UUID.randomUUID().toString();
        a2aBus.publish(new A2AMessage(
                "1.0",
                messageId,
                correlationId,
                "TaskRouterAgent",
                receiver,
                "",
                A2AIntent.DELEGATE_TASK,
                Map.of(
                        "taskType", request.taskType().name(),
                        "status", status.name()
                ),
                request.context() == null ? Map.of() : request.context(),
                status,
                null,
                new A2AMetadata("task-routing", "TaskRouterAgent", Map.of("taskType", request.taskType().name())),
                new A2ATrace(traceId, parentMessageId),
                Instant.now()
        ));
    }

    /**
     * 辅助工具：解析关联ID。
     */
    private String resolveCorrelationId(Map<String, Object> context) {
        String fromContext = readText(context, "correlationId");
        if (!fromContext.isBlank()) {
            return fromContext;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 辅助工具：解析链路追踪ID。
     */
    private String resolveTraceId(Map<String, Object> context) {
        String fromContext = readText(context, "traceId");
        if (!fromContext.isBlank()) {
            return fromContext;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 辅助工具：解析父消息ID。
     */
    private String resolveParentMessageId(Map<String, Object> context) {
        return readText(context, "parentMessageId");
    }

    /**
     * 辅助工具：解析结果回传方。
     */
    private String resolveReplyTo(Map<String, Object> context) {
        return readText(context, "replyTo");
    }

    /**
     * 向 A2A 总线发布结果回传（异步回包）消息。
     */
    private void publishReply(TaskResponse response, TaskType taskType, String replyTo, String correlationId, String traceId) {
        if (replyTo == null || replyTo.isBlank()) {
            return;
        }
        String messageId = UUID.randomUUID().toString();
        a2aBus.publish(new A2AMessage(
                "1.0",
                messageId,
                correlationId,
                "TaskRouterAgent",
                replyTo,
                "",
                A2AIntent.RETURN_RESULT,
                Map.of(
                        "taskType", taskType.name(),
                        "success", response.success(),
                        "message", response.message() == null ? "" : response.message(),
                        "data", response.data() == null ? Map.of() : response.data()
                ),
                Map.of(),
                response.success() ? A2AStatus.DONE : A2AStatus.FAILED,
                response.success() ? null : new com.example.interview.agent.a2a.A2AError("TASK_FAILED", response.message()),
                new A2AMetadata("task-routing-reply", "TaskRouterAgent", Map.of("taskType", taskType.name())),
                new A2ATrace(traceId, messageId),
                Instant.now()
        ));
    }
}
